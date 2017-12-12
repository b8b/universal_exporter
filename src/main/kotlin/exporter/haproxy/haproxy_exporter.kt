package exporter.haproxy

import exporter.Exporter
import exporter.MetricType
import exporter.MetricWriter
import exporter.readHeaders
import io.ktor.config.ApplicationConfig
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import kotlinx.coroutines.experimental.io.readUTF8Line
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URL

private suspend fun MetricWriter.metricValueIfNonNull(name: String, value: String?,
                                                      type: MetricType,
                                                      description: String,
                                                      vararg fields: Pair<String, String>) {
    if (value != null && !value.isBlank()) {
        metricValue(name, value.toLong(), type, description, *fields)
    }
}

private class Config(baseConfig: ApplicationConfig, endpointConfig: ApplicationConfig) {
    val url = URL((endpointConfig.propertyOrNull("url") ?: baseConfig.property("url")).getString())
}

class HAProxyExporter(baseConfig: ApplicationConfig, endpointConfigs: List<ApplicationConfig>?) : Exporter {

    override val instance = baseConfig.property("instance").getString()

    private val configList = endpointConfigs?.map { Config(baseConfig, it) } ?: listOf(Config(baseConfig, baseConfig))

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

    override suspend fun export(writer: MetricWriter) {
        val (socket, ex) = configList.map { c ->
            try {
                val port = if (c.url.port <= 0) 80 else c.url.port
                aSocket().tcp().connect(InetSocketAddress(c.url.host, port)) to null
            } catch (ex: IOException) {
                null to ex
            }
        }.last()
        if (socket == null) {
            throw ex ?: RuntimeException()
        }
        socket.use { socket ->
            val writeChannel = socket.openWriteChannel(true)
            writeChannel.writeFully("GET /;csv;norefresh HTTP/1.0\r\n\r\n".toByteArray())
            val readChannel = socket.openReadChannel()
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
    }

}
