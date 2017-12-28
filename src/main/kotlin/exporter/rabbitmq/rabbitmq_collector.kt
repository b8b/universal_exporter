package exporter.rabbitmq

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.async.ByteArrayFeeder
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.typesafe.config.Config
import exporter.*
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.net.NetSocket
import io.vertx.core.streams.ReadStream
import io.vertx.core.streams.WriteStream
import io.vertx.kotlin.core.net.NetClientOptions
import io.vertx.kotlin.coroutines.awaitResult
import io.vertx.kotlin.coroutines.toChannel
import kotlinx.coroutines.experimental.withTimeout
import java.io.IOException
import java.net.URL
import java.util.*
import java.util.concurrent.TimeUnit

private data class ExchangeMessageStats(val publish_in: Long, val publish_out: Long)

private data class Exchange(
        val vhost: String,
        val name: String,
        val message_stats: ExchangeMessageStats?)

private data class QueueMessageStats(val publish: Long)

private data class Queue(
        val vhost: String,
        val name: String,

        val messages: Long,
        val messages_ready: Long,
        val messages_unacknowledged: Long,
        val head_message_timestamp: Int?,
        val message_stats: QueueMessageStats?
)

data class RabbitMQConfig(val instance: String, val endpoints: List<RabbitMQEndpoint>) {
    constructor(config: Config) : this(
            config.getString("instance"),
            if (config.hasPath("endpoints")) {
                config.getConfigList("endpoints").map { it.withFallback(config) }
            } else {
                listOf(config)
            }.map(::RabbitMQEndpoint)
    )
}

data class RabbitMQEndpoint(val url: URL, val encodedCredentials: String) {
    constructor(config: Config) : this(
            URL(config.getString("url")),
            Base64.getUrlEncoder().encodeToString("${config.getString("username")}:${config.getString("password")}".toByteArray())
    )
}

class RabbitMQCollector(private val vertx: Vertx, val config: RabbitMQConfig) : Collector {

    override val instance: String get() = config.instance

    private val client = vertx.createNetClient(NetClientOptions(connectTimeout = 3000))

    private val objectMapper = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    private val exchangeObjectReader = objectMapper.readerFor(Exchange::class.java)
    private val queueObjectReader = objectMapper.readerFor(Queue::class.java)

    private suspend fun writeExchangeMetrics(writer: MetricWriter, x: Exchange,
                                             vararg fields: Pair<String, String>) {
        writer.metricValue("rmq_messages_out", x.message_stats?.publish_out ?: -1L,
                MetricType.Counter, "Count of messages acknowledged on queue or published out of exchange.", *fields)

        writer.metricValue("rmq_messages_in", x.message_stats?.publish_in ?: -1L,
                MetricType.Counter, "Count of messages published on queue or exchange.", *fields)
    }

    private suspend fun writeQueueMetrics(writer: MetricWriter, q: Queue,
                                     vararg fields: Pair<String, String>) {
        writer.metricValue("rmq_messages_total", q.messages,
                MetricType.Gauge, "Sum of ready and unacknowledged messages (queue depth).", *fields)

        writer.metricValue("rmq_messages_ready", q.messages_ready,
                MetricType.Gauge, "Number of messages ready to be delivered to clients.", *fields)

        writer.metricValue("rmq_messages_unack", q.messages_unacknowledged,
                MetricType.Gauge, "Number of messages delivered to clients but not yet acknowledged.", *fields)

        writer.metricValue("rmq_head_message_ts", q.head_message_timestamp ?: -1,
                MetricType.Gauge, "The timestamp property of the first message in the queue, if present." +
                " Timestamps of messages only appear when they are in the paged-in state.", *fields)

        writer.metricValue("rmq_messages_out", q.message_stats?.let { it.publish - q.messages } ?: -1L,
                MetricType.Counter, "Count of messages acknowledged on queue or published out of exchange.", *fields)

        writer.metricValue("rmq_messages_in", q.message_stats?.publish ?: -1L,
                MetricType.Counter, "Count of messages published on queue or exchange.", *fields)
    }

    override suspend fun export(writer: MetricWriter) {
        export(writer, "exchanges")
        export(writer, "queues")
    }

    private suspend fun connect(): Pair<RabbitMQEndpoint, NetSocket> {
        for (endpointConfig in config.endpoints) {
            try {
                val port = if (endpointConfig.url.port <= 0) 80 else endpointConfig.url.port
                val socket = awaitResult<NetSocket> {
                    client.connect(port, endpointConfig.url.host, it)
                }
                return Pair(endpointConfig, socket)
            } catch (ex: IOException) {
                if (endpointConfig === config.endpoints.last()) {
                    throw ex
                }
            }
        }
        throw IllegalStateException()
    }

    private suspend fun export(writer: MetricWriter, endpoint: String) {
        val (config, socket) = connect()
        try {
            withTimeout(3, TimeUnit.SECONDS) {
                val sendChannel = (socket as WriteStream<Buffer>).toChannel(vertx)
                sendChannel.send(Buffer.buffer("GET ${config.url.path}/$endpoint HTTP/1.1\r\n" +
                        "Authorization: Basic ${config.encodedCredentials}\r\n" +
                        "Connection: close\r\n" +
                        "\r\n"))

                val readChannel = (socket as ReadStream<Buffer>).toByteChannel(vertx)

                //read http headers
                readHeaders(readChannel)

                val buffer = ByteArray(1024 * 4)
                val objBuffer = JsonObjectBuffer()

                val p = JsonFactory().createNonBlockingByteArrayParser()
                parse@ while (true) {
                    val token = p.nextToken()
                    when (token) {
                        null -> {
                            //EOF
                            break@parse
                        }
                        JsonToken.NOT_AVAILABLE -> {
                            with(p.nonBlockingInputFeeder as ByteArrayFeeder) {
                                val read = readChannel.readAvailable(buffer)
                                if (read < 0) {
                                    endOfInput()
                                } else {
                                    feedInput(buffer, 0, read)
                                }
                            }
                        }
                        else -> objBuffer.processEvent(p)?.let {
                            when (endpoint) {
                                "exchanges" -> {
                                    val x = exchangeObjectReader.readValue<Exchange>(it.asParserOnFirstToken())
                                    writeExchangeMetrics(writer, x, "instance" to instance,
                                            "vhost" to x.vhost,
                                            "exchange" to x.name)
                                }
                                else -> {
                                    val q = queueObjectReader.readValue<Queue>(it.asParserOnFirstToken())
                                    writeQueueMetrics(writer, q, "instance" to instance,
                                            "vhost" to q.vhost,
                                            "queue" to q.name)
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            socket.close()
        }
    }
}
