package exporter.rabbitmq

import exporter.MetricType
import exporter.PrometheusOutput
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.application.call
import io.ktor.client.HttpClient
import io.ktor.client.backend.cio.CIOBackend
import io.ktor.client.bodyStream
import io.ktor.client.call.call
import io.ktor.client.request.header
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
import java.util.*

private class Config(private val config: ApplicationConfig) {
    val url = URL(config.property("url").getString())
    val username get() = config.property("username").getString()
    val password get() = config.property("password").getString()
    val encodedCredentials = Base64.getUrlEncoder().encodeToString(
            "$username:$password".toByteArray())
}

private data class MessageStats(val ack: Long, val publish: Long)

private data class Queue(
        val vhost: String, val name: String,

        val messages: Long,
        val messages_ready: Long,
        val messages_unacknowledged: Long,
        val head_message_timestamp: Date?,
        val message_stats: MessageStats?
)

private suspend fun queryMetrics(vararg config: Config): HttpResponse {
    val client = HttpClient({ CIOBackend() })
    throw config.map { c ->
        try {
            return client.call {
                url {
                    scheme = c.url.protocol
                    host = c.url.host
                    port = if (c.url.port <= 0) 80 else c.url.port
                    path = "${c.url.path}queues"
                }
                header("Authorization", "Basic ${c.encodedCredentials}")
            }
        } catch (ex: IOException) {
            ex
        }
    }.last()
}

@location("/metrics/rabbitmq/{instance}") class rabbitmq(val instance: String)

fun Route.rabbitmq_exporter(appConfig: ApplicationConfig) {
    val objectReader = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .readerFor(Queue::class.java)

    get<rabbitmq> { location ->
        val config = appConfig.configList(location.instance).map { Config(it) }
        queryMetrics(*config.toTypedArray()).use { httpResponse ->
            if (httpResponse.status.value != 200) {
                throw RuntimeException("rabbitmq returned status ${httpResponse.status}")
            }
            httpResponse.bodyStream.use { input ->
                val queues = objectReader.readValues<Queue>(input).asSequence()
                val metricValues = queues.flatMap { q ->
                    val fields = arrayOf("env" to location.instance, "vhost" to q.vhost, "queue" to q.name)
                    listOf(
                            metricValue("queue_messages", q.messages, MetricType.Gauge,
                                    "Sum of ready and unacknowledged messages (queue depth).",
                                    *fields),
                            metricValue("queue_messages_ready", q.messages_ready, MetricType.Gauge,
                                    "Number of messages ready to be delivered to clients.",
                                    *fields),
                            metricValue("queue_messages_unacknowledged", q.messages_unacknowledged, MetricType.Gauge,
                                    "Number of messages delivered to clients but not yet acknowledged.",
                                    *fields),
                            metricValue("queue_head_message_timestamp", q.head_message_timestamp?.time ?: 0, MetricType.Gauge,
                                    "The timestamp property of the first message in the queue, if present." +
                                            " Timestamps of messages only appear when they are in the paged-in state.",
                                    *fields),
                            metricValue("queue_messages_ack_total", q.message_stats?.ack ?: 0, MetricType.Counter,
                                    "Count of messages delivered in acknowledgement mode in response to basic.get.",
                                    *fields),
                            metricValue("queue_messages_published_total", q.message_stats?.publish ?: 0, MetricType.Counter,
                                    "Count of messages published.",
                                    *fields)
                    ).asSequence()
                }
                call.response.contentType(ContentType.Text.Plain.withParameter("version", "0.0.4"))
                call.respond(PrometheusOutput(metricValues))
            }
        }
    }
}
