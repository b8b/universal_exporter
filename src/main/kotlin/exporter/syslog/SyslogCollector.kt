package exporter.syslog

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

data class SyslogConfig(val port: Int) {
    constructor(config: Config) : this(
            config.getInt("port")
    )
}

class SyslogCollector(private val vertx: Vertx, val config: SyslogConfig) : Collector {

    override val instance: String
        get() = "localhost:${config.port}"

    suspend override fun export(writer: MetricWriter) {
        writer.metricValue("syslog_up", 1, MetricType.Gauge,
                "syslog exporter", "instance" to "${config.port}")
    }

    private val log = LoggerFactory.getLogger(javaClass)

    private fun handleConnect(client: NetSocket) {
        log.info("new connection from ${client.remoteAddress()}")
        launch(vertx.dispatcher()) {
            var counter = 0
            try {
                val ch = (client as ReadStream<Buffer>).toReceiveChannel(vertx)
                val parser = Progressive2(ch)
                try {
                    while (parser.parse5424()) {
                        parser.skipMesage()
                        counter++
                    }
                } catch (ex: Exception) {
                    log.warn("error in entry $counter: $ex")
                    throw ex
                }
            } finally {
                log.info("closing connection to {} (processed {} records)",
                        client.remoteAddress(), counter)
                client.close()
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