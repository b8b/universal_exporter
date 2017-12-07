package exporter.haproxy

import exporter.Exporter
import exporter.MetricType
import exporter.MetricWriter
import io.ktor.client.HttpClient
import io.ktor.client.backend.cio.CIOBackend
import io.ktor.client.bodyStream
import io.ktor.client.call.call
import io.ktor.client.response.HttpResponse
import io.ktor.config.ApplicationConfig
import java.io.IOException
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

    private suspend fun queryMetrics(): HttpResponse {
        val client = HttpClient({ CIOBackend() })
        //TODO query all endpoints concurrently and aggregate metrics
        throw configList.map { c ->
            try {
                return client.call {
                    url {
                        scheme = c.url.protocol
                        host = c.url.host
                        port = if (c.url.port <= 0) 80 else c.url.port
                        path = "${c.url.path};csv;norefresh"
                    }
                }
            } catch (ex: IOException) {
                ex
            }
        }.last()
    }

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
        queryMetrics().use { httpResponse ->
            if (httpResponse.status.value != 200) {
                throw RuntimeException("haproxy returned status ${httpResponse.status}")
            }
            httpResponse.bodyStream.use { input ->
                input.bufferedReader().use { reader ->
                    val inputLines = reader.lineSequence().iterator()
                    //read column headers on first line
                    val firstLine = inputLines.next()
                    val headers = firstLine.trimStart('#')
                            .splitToSequence(',')
                            .map { it.trim() }
                            .toList()
                    //read values on other lines
                    inputLines.asSequence().forEach { line ->
                        val record = line
                                .splitToSequence(',')
                                .mapIndexed { i, s -> headers[i] to s.trim() }
                                .toMap()
                        val pxname = record["pxname"]
                        val svname = record["svname"]
                        if (pxname == null || svname == null) {
                            return@forEach
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
    }

}
