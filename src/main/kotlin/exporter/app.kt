package exporter

import exporter.ganglia.GangliaExporter
import exporter.haproxy.HAProxyExporter
import exporter.rabbitmq.RabbitMQExporter
import io.ktor.application.Application
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.config.ApplicationConfig
import io.ktor.features.CallLogging
import io.ktor.features.Compression
import io.ktor.features.DefaultHeaders
import io.ktor.locations.Locations
import io.ktor.response.respond
import io.ktor.routing.Route
import io.ktor.routing.Routing
import io.ktor.routing.application
import io.ktor.routing.get
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.commandLineEnvironment
import java.util.concurrent.TimeUnit

private fun ApplicationConfig.configListOrNull(path: String) = try {
    this.configList(path)
} catch (ex: Exception) {
    null
}

private fun Route.exporter(name: String, factory: (ApplicationConfig) -> Exporter) {
    application.environment.config.configListOrNull(name)?.let { configs ->
        val exporters = configs.map { cfg ->
            factory(cfg)
        }
        exporters.forEach { exporter ->
            get("/metrics/$name/${exporter.instance}") {
                call.respond(PrometheusOutput(exporter))
            }
        }
        get("/metrics/$name") {
            call.respond(PrometheusOutput(*exporters.toTypedArray()))
        }
    }
}

fun Application.module() {
    install(DefaultHeaders)
    install(Compression)
    install(CallLogging)
    install(Locations)

    install(Routing) {
        exporter("haproxy") { cfg ->
            HAProxyExporter(cfg, cfg.configListOrNull("endpoints"))
        }

        exporter("rabbitmq") { cfg ->
            RabbitMQExporter(cfg, cfg.configListOrNull("endpoints"))
        }

        exporter("ganglia") { cfg ->
            GangliaExporter(cfg, cfg.configListOrNull("endpoints"))
        }

        //build 2 level toc
        val exporters = children.flatMap { route -> route.children.map(Route::toString) }
        index(exporters)
    }
}

fun main(args: Array<String>) {
    val applicationEnvironment = commandLineEnvironment(args)
    val engine = CIOApplicationEngine(applicationEnvironment) {
        callGroupSize = 1
        workerGroupSize = 1
        connectionGroupSize = 1
    }
    Runtime.getRuntime().addShutdownHook(object : Thread() {
        override fun run() {
            engine.stop(3, 5, TimeUnit.SECONDS)
        }
    })
    engine.start(true)
}
