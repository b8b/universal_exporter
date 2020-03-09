package org.cikit.modules.rabbitmq

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.async.ByteArrayFeeder
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpClient
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.http.HttpClientResponse
import io.vertx.core.streams.ReadStream
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.kotlin.coroutines.toChannel
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.cikit.core.Collector
import org.cikit.core.JsonObjectBuffer
import org.cikit.core.MetricType
import org.cikit.core.MetricWriter

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
        val head_message_timestamp: Long?,
        val head_message_age: Long?,
        val message_stats: QueueMessageStats?
)

class RabbitMQCollector(private val vx: Vertx, private val config: RabbitMQConfig) : Collector {

    override val instance: String get() = config.instance

    private var _client: HttpClient? = null
    private fun client(): HttpClient {
        return _client ?: let {
            val newClient = vx.createHttpClient(HttpClientOptions()
                    .setConnectTimeout(3000))
            _client = newClient
            newClient
        }
    }

    private val objectMapper = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    private val exchangeObjectReader = objectMapper.readerFor(Exchange::class.java)
    private val queueObjectReader = objectMapper.readerFor(Queue::class.java)

    private suspend fun writeExchangeMetrics(writer: MetricWriter, x: Exchange,
                                             vararg fields: Pair<String, String>) {
        writer.metricValue("rmq_messages_out", x.message_stats?.publish_out
                ?: -1L,
                MetricType.Counter, "Count of messages acknowledged on queue or published out of exchange.", *fields)

        writer.metricValue("rmq_messages_in", x.message_stats?.publish_in
                ?: -1L,
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

        writer.metricValue("rmq_head_message_ts", q.head_message_timestamp
                ?: -1,
                MetricType.Gauge, "The timestamp property of the first message in the queue, if present." +
                " Timestamps of messages only appear when they are in the paged-in state.", *fields)

        writer.metricValue("rmq_head_message_age", q.head_message_age ?: -1,
                MetricType.Gauge, "The age of the first message in the queue, calculated via head_message_ts." +
                " Timestamps of messages only appear when they are in the paged-in state.", *fields)

        writer.metricValue("rmq_messages_out", q.message_stats?.let { it.publish - q.messages }
                ?: -1L,
                MetricType.Counter, "Count of messages acknowledged on queue or published out of exchange.", *fields)

        writer.metricValue("rmq_messages_in", q.message_stats?.publish ?: -1L,
                MetricType.Counter, "Count of messages published on queue or exchange.", *fields)
    }

    override suspend fun export(writer: MetricWriter) {
        export(writer, "exchanges")
        export(writer, "queues")
    }

    private suspend fun connect(path: String): Pair<RabbitMQEndpoint, HttpClientResponse> {
        val respAvailChannel = Channel<HttpClientResponse>()
        val endpoint = config.endpoints.random()
        val url = endpoint.url
        val port = if (url.port <= 0) 80 else url.port
        val uri = "${url.path}/$path"
        val req = client().get(port, url.host, uri) { resp ->
            GlobalScope.launch(vx.dispatcher()) {
                respAvailChannel.send(resp)
            }
        }
        req.exceptionHandler { ex ->
            respAvailChannel.close(ex)
        }
        req.putHeader("user-agent", "Cowgirl/0.1")
        req.putHeader("accept", "*/*")
        req.putHeader("authorization", "Basic ${endpoint.encodedCredentials}")
        req.end()
        return Pair(endpoint, respAvailChannel.receive())
    }

    private suspend fun export(writer: MetricWriter, endpoint: String) {
        withTimeout(3_000L) {
            val (_, socket) = connect(endpoint)
            val readChannel = (socket as ReadStream<Buffer>).toChannel(vx)

            val p = JsonFactory().createNonBlockingByteArrayParser()
            val objBuffer = JsonObjectBuffer()

            for (buffer in readChannel) {
                val bytes = buffer.bytes
                (p.nonBlockingInputFeeder as ByteArrayFeeder)
                        .feedInput(bytes, 0, bytes.size)
                while (true) {
                    val ev = p.nextToken() ?: break
                    if (ev == JsonToken.NOT_AVAILABLE) {
                        break
                    }
                    processEvent(p, objBuffer, writer, endpoint)
                }
            }

            p.nonBlockingInputFeeder.endOfInput()
            while (true) {
                val ev = p.nextToken() ?: break
                if (ev == JsonToken.NOT_AVAILABLE) {
                    error("NOT_AVAILABLE after endOfInput")
                }
                processEvent(p, objBuffer, writer, endpoint)
            }
        }
    }

    private suspend fun processEvent(
            p: JsonParser, objBuffer: JsonObjectBuffer,
            writer: MetricWriter, endpoint: String
    ) {
        objBuffer.processEvent(p)?.let {
            val now = System.currentTimeMillis()
            when (endpoint) {
                "exchanges" -> {
                    val x = exchangeObjectReader.readValue<Exchange>(it.asParserOnFirstToken())
                    writeExchangeMetrics(writer, x, "instance" to instance,
                            *(config.labels.map { (k, v) -> k to v }.toTypedArray()),
                            "vhost" to x.vhost,
                            "exchange" to x.name)
                }
                else -> {
                    val q = with(queueObjectReader.readValue<Queue>(it.asParserOnFirstToken())) {
                        if (head_message_timestamp == null) this
                        else this.copy(head_message_age = now - head_message_timestamp)
                    }
                    writeQueueMetrics(writer, q, "instance" to instance,
                            *(config.labels.map { (k, v) -> k to v }.toTypedArray()),
                            "vhost" to q.vhost,
                            "queue" to q.name)
                }
            }
        }
    }
}
