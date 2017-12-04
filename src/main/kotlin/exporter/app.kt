package exporter

import exporter.haproxy.haproxy_exporter
import exporter.rabbitmq.rabbitmq_exporter
import io.ktor.application.Application
import io.ktor.application.install
import io.ktor.features.CallLogging
import io.ktor.features.Compression
import io.ktor.features.DefaultHeaders
import io.ktor.locations.Locations
import io.ktor.routing.Routing
import io.ktor.server.engine.commandLineEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.jetty.Jetty

fun Application.module() {
    install(DefaultHeaders)
    install(Compression)
    install(CallLogging)
    install(Locations)

    install(Routing) {
        index()
        rabbitmq_exporter(environment.config.config("rabbitmq"))
        haproxy_exporter(environment.config.config("haproxy"))
    }
}

fun main(args: Array<String>) {
    embeddedServer(Jetty, commandLineEnvironment(args)).start()
}
