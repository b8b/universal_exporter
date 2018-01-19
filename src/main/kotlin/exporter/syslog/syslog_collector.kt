package exporter.syslog

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.async.ByteArrayFeeder
import com.typesafe.config.Config
import exporter.Collector
import exporter.MetricType
import exporter.MetricWriter
import exporter.toReceiveChannel
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.net.NetServer
import io.vertx.core.net.NetSocket
import io.vertx.core.streams.ReadStream
import io.vertx.kotlin.core.net.NetServerOptions
import io.vertx.kotlin.coroutines.awaitResult
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.experimental.launch
import org.slf4j.LoggerFactory
import rfc5424.Progressive2

data class SyslogConfig(val port: Int) {
    constructor(config: Config) : this(
            config.getInt("port")
    )
}

class SyslogCollector(private val vertx: Vertx, val config: SyslogConfig) : Collector {

    private var clientsConnectedTotal: Long = 0
    private var clientsDisconnectedTotal: Long = 0
    private var messagesProcessedTotal: Long = 0
    private var bytesProcessedTotal: Long = 0

    override val instance: String
        get() = "localhost:${config.port}"

    suspend override fun export(writer: MetricWriter) {
        val fields = "instance" to config.port.toString()
        writer.metricValue("syslog_up", 1, MetricType.Gauge,
                "syslog exporter", fields)
        writer.metricValue("syslog_connected_total", clientsConnectedTotal,
                MetricType.Counter, "syslog clients connected", fields)
        writer.metricValue("syslog_disconnected_total", clientsDisconnectedTotal,
                MetricType.Counter, "syslog clients disconnected", fields)
        writer.metricValue("syslog_messages_total", messagesProcessedTotal,
                MetricType.Counter, "syslog messages processed", fields)
        writer.metricValue("syslog_bytes_total", bytesProcessedTotal,
                MetricType.Counter, "syslog bytes processed", fields)
    }

    private val log = LoggerFactory.getLogger(javaClass)

    private fun handleConnect(client: NetSocket) {
        clientsConnectedTotal++
        log.info("new connection from ${client.remoteAddress()}")
        launch(vertx.dispatcher()) {
            var counter = 0
            var bytesConsumedLast = 0L
            try {
                val ch = (client as ReadStream<Buffer>).toReceiveChannel(vertx)
                val parser = Progressive2(ch)
                try {
                    while (parser.parse5424()) {
                        var block = parser.readMessageAvailable() ?: break
                        if (block[block.position()] == '{'.toByte()) {
                            val jsonParser = JsonFactory().createNonBlockingByteArrayParser()
                            parse@ while (true) {
                                val token = jsonParser.nextToken()
                                when (token) {
                                    null -> {
                                        //EOF
                                        break@parse
                                    }
                                    JsonToken.NOT_AVAILABLE -> {
                                        with(jsonParser.nonBlockingInputFeeder as ByteArrayFeeder) {
                                            val offset = block.arrayOffset() + block.position()
                                            val length = block.remaining()
                                            feedInput(block.array(), offset, offset + length)
                                            val nextBlock = parser.readMessageAvailable()
                                            if (nextBlock != null) block = nextBlock else endOfInput()
                                        }
                                    }
                                }
                            }
                        } else {
                            parser.skipMessage()
                        }
                        counter++
                        messagesProcessedTotal++
                        parser.bytesConsumed().let {
                            bytesProcessedTotal += it - bytesConsumedLast
                            bytesConsumedLast = it
                        }
                    }
                } catch (ex: Exception) {
                    log.warn("error in entry ${counter.inc()}: $ex")
                    throw ex
                }
            } finally {
                log.info("closing connection to {} (processed {} records)",
                        client.remoteAddress(), counter)
                client.close()
                clientsDisconnectedTotal++
            }
        }
    }

    suspend fun start() {
        awaitResult<NetServer> {
            vertx.createNetServer(NetServerOptions(receiveBufferSize = 4096))
                    .connectHandler(::handleConnect)
                    .listen(config.port, it)
        }
    }
}