package exporter.rabbitmq

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import exporter.Exporter
import exporter.MetricType
import exporter.MetricWriter
import io.ktor.client.HttpClient
import io.ktor.client.backend.cio.CIOBackend
import io.ktor.client.bodyStream
import io.ktor.client.call.call
import io.ktor.client.request.header
import io.ktor.client.response.HttpResponse
import io.ktor.config.ApplicationConfig
import java.io.IOException
import java.net.URL
import java.util.*

private class Config(baseConfig: ApplicationConfig, endpointConfig: ApplicationConfig) {
    val url = URL((endpointConfig.propertyOrNull("url") ?: baseConfig.property("url")).getString())
    val username = (endpointConfig.propertyOrNull("username") ?: baseConfig.property("username")).getString()
    val password = (endpointConfig.propertyOrNull("password") ?: baseConfig.property("password")).getString()
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

class RabbitMQExporter(baseConfig: ApplicationConfig, endpointConfigs: List<ApplicationConfig>?) : Exporter {

    override val instance = baseConfig.property("instance").getString()

    private val configList = endpointConfigs?.map { Config(baseConfig, it) } ?: listOf(Config(baseConfig, baseConfig))

    private val objectReader = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .readerFor(Queue::class.java)

    private suspend fun queryMetrics(): HttpResponse {
        val client = HttpClient({ CIOBackend() })
        throw configList.map { c ->
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

    private suspend fun writeMetrics(writer: MetricWriter, q: Queue,
                                     vararg fields: Pair<String, String>) {
        writer.metricValue("queue_messages", q.messages,
                MetricType.Gauge, "Sum of ready and unacknowledged messages (queue depth).", *fields)

        writer.metricValue("queue_messages_ready", q.messages_ready,
                MetricType.Gauge, "Number of messages ready to be delivered to clients.", *fields)

        writer.metricValue("queue_messages_unacknowledged", q.messages_unacknowledged,
                MetricType.Gauge, "Number of messages delivered to clients but not yet acknowledged.", *fields)

        writer.metricValue("queue_head_message_timestamp", q.head_message_timestamp?.time ?: 0,
                MetricType.Gauge, "The timestamp property of the first message in the queue, if present." +
                " Timestamps of messages only appear when they are in the paged-in state.", *fields)

        writer.metricValue("queue_messages_ack_total", q.message_stats?.ack ?: 0,
                MetricType.Counter, "Count of messages delivered in acknowledgement mode in response to basic.get.", *fields)

        writer.metricValue("queue_messages_published_total", q.message_stats?.publish ?: 0,
                MetricType.Counter, "Count of messages published.", *fields)
    }

    override suspend fun export(writer: MetricWriter) {
        queryMetrics().use { httpResponse ->
            if (httpResponse.status.value != 200) {
                throw RuntimeException("rabbitmq returned status ${httpResponse.status}")
            }
            httpResponse.bodyStream.use { input ->
                objectReader.readValues<Queue>(input).forEach { q ->
                    writeMetrics(writer, q, "instance" to instance,
                            "vhost" to q.vhost,
                            "queue" to q.name)
                }
            }
        }
    }

}
