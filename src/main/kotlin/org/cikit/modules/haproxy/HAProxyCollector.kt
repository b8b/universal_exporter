package org.cikit.modules.haproxy

import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpClient
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.http.HttpClientResponse
import io.vertx.core.parsetools.RecordParser
import io.vertx.core.streams.ReadStream
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.kotlin.coroutines.toChannel
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.cikit.core.Collector
import org.cikit.core.MetricType
import org.cikit.core.MetricWriter
import org.slf4j.LoggerFactory
import java.net.URL

private suspend fun MetricWriter.metricValueIfNonNull(
        name: String, value: String?,
        type: MetricType,
        description: String,
        vararg fields: Pair<String, String>
) {
    if (value != null && !value.isBlank()) {
        metricValue(name, value.toLong(), type, description, *fields)
    }
}

class HAProxyCollector(private val vx: Vertx, private val config: HAProxyConfig) : Collector {

    override val instance: String get() = config.instance

    private val log = LoggerFactory.getLogger("haproxy")

    private var _client: HttpClient? = null
    private fun client(): HttpClient {
        return _client ?: let {
            val newClient = vx.createHttpClient(HttpClientOptions()
                    .setConnectTimeout(3000))
            _client = newClient
            newClient
        }
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

    private suspend fun connect(): Pair<URL, HttpClientResponse> {
        val respAvailChannel = Channel<HttpClientResponse>()
        val url = config.endpoints.random()
        val port = if (url.port <= 0) 80 else url.port
        val uri = url.path.removeSuffix("/;csv;norefresh") +
                "/;csv;norefresh"
        log.debug("[${url.host}:$port] GET $uri")
        val req = client().get(port, url.host, uri) { resp ->
            GlobalScope.launch(vx.dispatcher()) {
                respAvailChannel.send(resp)
            }
        }
        req.exceptionHandler { ex ->
            respAvailChannel.close(ex)
        }
        req.end()
        return Pair(url, respAvailChannel.receive())
    }

    override suspend fun export(writer: MetricWriter) {
        withTimeout(3_000L) {
            val (_, socket) = connect()

            //check status
            if (socket.statusCode() != 200) {
                error("haproxy respond with status ${socket.statusCode()}")
            }

            val readChannel = RecordParser
                    .newDelimited("\n", socket as ReadStream<Buffer>)
                    .toChannel(vx)

            //read csv headers
            val firstLine = readChannel.receive()
            val headers = firstLine.toString(Charsets.UTF_8)
                    .trimStart('#')
                    .splitToSequence(',')
                    .map { it.trim() }
                    .toList()
            //read csv lines
            for (buffer in readChannel) {
                val line = buffer.toString(Charsets.UTF_8)
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
                } + config.labels.map { (k, v) -> k to v }.toTypedArray()
                writeMetrics(writer, record, *fields, "instance" to instance)
            }
        }
    }

}
