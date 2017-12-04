package exporter

import exporter.haproxy.haproxy_exporter
import exporter.rabbitmq.rabbitmq_exporter
import io.ktor.application.Application
import io.ktor.application.install
import io.ktor.config.ApplicationConfig
import io.ktor.features.CallLogging
import io.ktor.features.Compression
import io.ktor.features.DefaultHeaders
import io.ktor.locations.Locations
import io.ktor.routing.Route
import io.ktor.routing.Routing
import io.ktor.server.engine.commandLineEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

private fun ApplicationConfig.configOrNull(path: String) = try {
    this.config(path)
} catch (ex: Exception) {
    null
}

fun Application.module() {
    install(DefaultHeaders)
    install(Compression)
    install(CallLogging)
    install(Locations)

    install(Routing) {
        environment.config.configOrNull("rabbitmq")?.let { rabbitmq_exporter(it) }
        environment.config.configOrNull("haproxy")?.let { haproxy_exporter(it) }

        //build 2 level toc
        val exporters = children.flatMap { route -> route.children.map(Route::toString) }
        index(exporters)
    }
}

fun main(args: Array<String>) {
    embeddedServer(Netty, commandLineEnvironment(args)) {
        callGroupSize = 1
        workerGroupSize = 1
        connectionGroupSize = 1
    }.start()
}
