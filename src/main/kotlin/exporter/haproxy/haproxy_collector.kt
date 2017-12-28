package exporter.haproxy

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
import kotlinx.coroutines.experimental.io.readUTF8Line
import kotlinx.coroutines.experimental.withTimeout
import java.io.IOException
import java.net.URL
import java.util.concurrent.TimeUnit

private suspend fun MetricWriter.metricValueIfNonNull(name: String, value: String?,
                                                      type: MetricType,
                                                      description: String,
                                                      vararg fields: Pair<String, String>) {
    if (value != null && !value.isBlank()) {
        metricValue(name, value.toLong(), type, description, *fields)
    }
}

data class HAProxyConfig(val instance: String, val endpoints: List<URL>) {
    constructor(config: Config) : this(
            config.getString("instance"),
            if (config.hasPath("endpoints")) {
                config.getConfigList("endpoints").map { it.withFallback(config) }
            } else {
                listOf(config)
            }.map { URL(it.getString("url")) }
    )
}

class HAProxyCollector(private val vertx: Vertx, val config: HAProxyConfig) : Collector {

    override val instance: String get() = config.instance

    private val client = vertx.createNetClient(NetClientOptions(connectTimeout = 3000))

    private suspend fun writeMetrics(writer: MetricWriter, record: Map<String, String>,
                                     vararg fields: Pair<String, String>) {
        writer.metricValueIfNonNull("hap_qtime", record["qtime"],
                MetricType.Gauge, "queue time ms", *fields)

        writer.metricValueIfNonNull("hap_ctime", record["ctime"],
                MetricType.Gauge, "connect time ms", *fields)

        writer.metricValueIfNonNull("hap_rtime", record["rtime"],
                MetricType.Gauge, "response time ms", *fields)

        writer.metricValueIfNonNull("hap_ttime", record["ttime"],
                MetricType.Gauge, "total time ms", *fields)

        writer.metricValueIfNonNull("hap_scur", record["scur"],
                MetricType.Gauge, "sessions current", *fields)

        writer.metricValueIfNonNull("hap_smax", record["smax"],
                MetricType.Gauge, "sessions maximum", *fields)

        writer.metricValueIfNonNull("hap_slim", record["slim"],
                MetricType.Gauge, "session limit", *fields)

        writer.metricValueIfNonNull("hap_rate", record["rate"],
                MetricType.Gauge, "session rate current", *fields)

        writer.metricValueIfNonNull("hap_rate_max", record["rate_max"],
                MetricType.Gauge, "session rate maximum", *fields)

        writer.metricValueIfNonNull("hap_rate_lim", record["rate_lim"],
                MetricType.Gauge, "session rate limit", *fields)

        for (i in 1..5) {
            writer.metricValueIfNonNull("hap_hrsp_${i}xx", record["hrsp_${i}xx"],
                    MetricType.Counter, "${i}xx responses", *fields)
        }

        writer.metricValueIfNonNull("hap_hrsp_other", record["hrsp_other"],
                MetricType.Counter, "other responses", *fields)
    }

    private suspend fun connect(): Pair<URL, NetSocket> {
        for (url in config.endpoints) {
            try {
                val port = if (url.port <= 0) 80 else url.port
                val socket = awaitResult<NetSocket> {
                    client.connect(port, url.host, it)
                }
                return Pair(url, socket)
            } catch (ex: IOException) {
                if (url === config.endpoints.last()) {
                    throw ex
                }
            }
        }
        throw IllegalStateException()
    }

    override suspend fun export(writer: MetricWriter) {
        val (_, socket) = connect()
        try {
            withTimeout(3, TimeUnit.SECONDS) {
                val sendChannel = (socket as WriteStream<Buffer>).toChannel(vertx)
                sendChannel.send(Buffer.buffer("GET /;csv;norefresh HTTP/1.0\r\n\r\n"))

                val readChannel = (socket as ReadStream<Buffer>).toByteChannel(vertx)

                //read http headers
                readHeaders(readChannel)

                //read csv headers
                val firstLine = readChannel.readUTF8Line() ?: throw IllegalStateException()
                val headers = firstLine.trimStart('#')
                        .splitToSequence(',')
                        .map { it.trim() }
                        .toList()
                //read csv lines
                while (true) {
                    val line = readChannel.readUTF8Line()
                    if (line == null || line.isEmpty()) {
                        break
                    }
                    val record = line
                            .splitToSequence(',')
                            .mapIndexed { i, s -> headers[i] to s.trim() }
                            .toMap()
                    val pxname = record["pxname"]
                    val svname = record["svname"]
                    if (pxname == null || svname == null) {
                        continue
                    }
                    val fields = when (svname) {
                        "FRONTEND" -> arrayOf("frontend" to pxname)
                        "BACKEND" -> arrayOf("backend" to pxname)
                        else -> arrayOf("backend" to pxname, "server" to svname)
                    }
                    writeMetrics(writer, record, *fields, "instance" to instance)
                }
            }
        } finally {
            socket.close()
        }
    }

}
