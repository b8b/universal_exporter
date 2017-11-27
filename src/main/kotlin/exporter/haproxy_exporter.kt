package exporter

import io.ktor.application.call
import io.ktor.cio.WriteChannel
import io.ktor.cio.toInputStream
import io.ktor.cio.toOutputStream
import io.ktor.client.HttpClient
import io.ktor.client.backend.cio.CIOBackend
import io.ktor.client.bodyChannel
import io.ktor.client.call.call
import io.ktor.client.utils.contentType
import io.ktor.content.OutgoingContent
import io.ktor.http.ContentType
import io.ktor.response.contentType
import io.ktor.response.respond
import io.ktor.routing.Route
import io.ktor.routing.get

fun Route.haproxy_exporter() {
    get("/metrics/haproxy") {
        val client = HttpClient({ CIOBackend() })
        client.call {
            url {
                scheme = "http"
                host = "172.19.10.3"
                port = 89
                path = "/;csv;norefresh"
            }
        }.use { httpResponse ->
            call.response.contentType(httpResponse.call.response.contentType() ?: ContentType.Application.OctetStream)
            httpResponse.bodyChannel.use { input ->
                val inputLines = input.toInputStream().bufferedReader().lineSequence().iterator()
                call.respond(object : OutgoingContent.WriteChannelContent() {
                    suspend override fun writeTo(channel: WriteChannel) {
                        channel.use { out ->
                            out.toOutputStream().bufferedWriter().use { writer ->
                                //parse column headers on first line
                                val firstLine = inputLines.next()
                                val keys = firstLine.trimStart('#')
                                        .splitToSequence(',')
                                        .map { it.trim() }
                                        .toList()
                                //transform metrics
                                inputLines.forEachRemaining { line ->
                                    val record = line
                                            .splitToSequence(',')
                                            .mapIndexed { i, s -> keys[i] to s }
                                            .toMap()
                                    writer.write(record.toString())
                                    writer.newLine()
                                }
                            }
                        }
                    }
                })
            }
        }
    }
}
