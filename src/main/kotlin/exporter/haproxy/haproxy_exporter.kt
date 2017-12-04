package exporter.haproxy

import exporter.MetricType
import exporter.MetricValue
import exporter.PrometheusOutput
import io.ktor.application.call
import io.ktor.client.HttpClient
import io.ktor.client.backend.cio.CIOBackend
import io.ktor.client.bodyStream
import io.ktor.client.call.call
import io.ktor.client.response.HttpResponse
import io.ktor.config.ApplicationConfig
import io.ktor.http.ContentType
import io.ktor.locations.get
import io.ktor.locations.location
import io.ktor.response.contentType
import io.ktor.response.respond
import io.ktor.routing.Route
import exporter.metricValue
import java.io.IOException
import java.net.URL

private class Config(private val config: ApplicationConfig) {
    val url = URL(config.property("url").getString())
}

private suspend fun queryMetrics(vararg config: Config): HttpResponse {
    val client = HttpClient({ CIOBackend() })
    throw config.map { c ->
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

private fun metricValueOrNull(name: String, value: String?, type: MetricType, description: String, vararg fields: Pair<String, String>): MetricValue? {
    return value?.let { if (it == "") null else metricValue(name, value.toLong(), type, description, *fields) }
}

@location("/metrics/haproxy/{instance}") class haproxy(val instance: String)

fun Route.haproxy_exporter(appConfig: ApplicationConfig) {
    get<haproxy> { location ->
        val config = appConfig.configList(location.instance).map { Config(it) }
        queryMetrics(*config.toTypedArray()).use { httpResponse ->
            httpResponse.bodyStream.use { input ->
                input.bufferedReader().use { reader ->
                    val inputLines = reader.lineSequence().iterator()
                    //parse column headers on first line
                    val firstLine = inputLines.next()
                    val keys = firstLine.trimStart('#')
                            .splitToSequence(',')
                            .map { it.trim() }
                            .toList()
                    val metricValues = inputLines.asSequence().flatMap { line ->
                        val record = line
                                .splitToSequence(',')
                                .mapIndexed { i, s -> keys[i] to s.trim() }
                                .toMap()
                        val pxname = record["pxname"]
                        val svname = record["svname"]
                        if (pxname == null || svname == null) {
                            return@flatMap emptySequence<MetricValue>()
                        }
                        val fields = when (svname) {
                            "FRONTEND" ->
                                arrayOf("env" to location.instance, "fromtend" to pxname)
                            "BACKEND" ->
                                arrayOf("env" to location.instance, "backend" to pxname)
                            else -> arrayOf("env" to location.instance, "backend" to pxname, "server" to svname)
                        }
                        listOf(
                                metricValueOrNull("hap_qtime", record["qtime"], MetricType.Gauge,
                                        "queue time ms", *fields),
                                metricValueOrNull("hap_ctime", record["ctime"], MetricType.Gauge,
                                        "connect time ms", *fields),
                                metricValueOrNull("hap_rtime", record["rtime"], MetricType.Gauge,
                                        "response time ms", *fields),
                                metricValueOrNull("hap_ttime", record["ttime"], MetricType.Gauge,
                                        "total time ms", *fields),

                                metricValueOrNull("hap_scur", record["scur"], MetricType.Gauge,
                                        "sessions current", *fields),
                                metricValueOrNull("hap_smax", record["smax"], MetricType.Gauge,
                                        "sessions maximum", *fields),
                                metricValueOrNull("hap_slim", record["slim"], MetricType.Gauge,
                                        "session limit", *fields),

                                metricValueOrNull("hap_rate", record["rate"], MetricType.Gauge,
                                        "session rate current", *fields),
                                metricValueOrNull("hap_rate_max", record["rate_max"], MetricType.Gauge,
                                        "session rate maximum", *fields),
                                metricValueOrNull("hap_rate_lim", record["rate_lim"], MetricType.Gauge,
                                        "session rate limit", *fields),

                                metricValueOrNull("hap_hrsp_1xx", record["hrsp_1xx"], MetricType.Counter,
                                        "1xx responses", *fields),
                                metricValueOrNull("hap_hrsp_2xx", record["hrsp_2xx"], MetricType.Counter,
                                        "2xx responses", *fields),
                                metricValueOrNull("hap_hrsp_3xx", record["hrsp_3xx"], MetricType.Counter,
                                        "3xx responses", *fields),
                                metricValueOrNull("hap_hrsp_4xx", record["hrsp_4xx"], MetricType.Counter,
                                        "4xx responses", *fields),
                                metricValueOrNull("hap_hrsp_other", record["hrsp_other"], MetricType.Counter,
                                        "other responses", *fields)
                        ).filterNotNull().asSequence()
                    }
                    call.response.contentType(ContentType.Text.Plain.withParameter("version", "0.0.4"))
                    call.respond(PrometheusOutput(metricValues))
                }
            }
        }
    }
}
