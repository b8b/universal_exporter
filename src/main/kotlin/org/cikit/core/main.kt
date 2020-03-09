package org.cikit.core

import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import io.vertx.core.http.HttpServer
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Route
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.coroutines.awaitResult
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.kotlin.coroutines.toChannel
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.nio.file.Paths
import java.util.*

private val log = LoggerFactory.getLogger("main")

private data class Config(
        val log: LoggingConfig? = null,
        val http: HttpConfig? = null,
        val modules: Map<String, List<Any>> = emptyMap()
)

private data class LoggingConfig(
        val file: String? = null
)

private data class HttpConfig(
        val port: Int = 8080
)

private class UniversalExporter : CliktCommand(
        name = "universal-exporter",
        help = "Collection of metrics exporters for prometheus") {

    private val configFile by option("-c", help = "config file").file()

    @ExperimentalCoroutinesApi
    override fun run() {
        System.setProperty(
                io.vertx.core.logging.LoggerFactory.LOGGER_DELEGATE_FACTORY_CLASS_NAME,
                io.vertx.core.logging.SLF4JLogDelegateFactory::class.java.name)
        LoggerFactory.getLogger(io.vertx.core.logging.LoggerFactory::class.java)

        val config = configFile?.let { f ->
            YAMLFactory().createParser(f).use { p ->
                jacksonObjectMapper().readValue<Config>(p)
            }
        } ?: Config()

        configureLogging(config.log?.file?.let { Paths.get(it) })
        start(config)
    }

}

fun Route.coroutineHandler(timeout: Long? = 10_000L, fn: suspend (RoutingContext) -> Unit) {
    handler { ctx ->
        val job = GlobalScope.launch(ctx.vertx().dispatcher()) {
            try {
                if (timeout == null) {
                    fn(ctx)
                } else {
                    withTimeout(timeout) {
                        fn(ctx)
                    }
                }
            } catch (e: Exception) {
                log.warn(ctx.request().path() + ": fail: $e")
                ctx.fail(e)
            }
        }
        ctx.request().connection().closeHandler {
            job.cancel(CancellationException("client disconnected"))
        }
    }
}

@ExperimentalCoroutinesApi
private fun start(config: Config) {
    val vx = Vertx.vertx(VertxOptions()
            .setBlockedThreadCheckInterval(10000)
            .setEventLoopPoolSize(1))

    val router = Router.router(vx)
    val endpoints = mutableSetOf<String>()

    ServiceLoader.load(Module::class.java).forEach { module ->
        val collectors = mutableListOf<Collector>()
        val moduleConfig = config.modules[module.name]
        val collectorFactory = module.collectorFactory
        if (collectorFactory != null && moduleConfig != null) {
            for (instanceConfig in moduleConfig) {
                collectors.add(collectorFactory(vx, JsonObject.mapFrom(instanceConfig)))
            }
        }
        val endpoint = "/metrics/${module.name}"
        endpoints.add(endpoint)
        router.get(endpoint).coroutineHandler { ctx ->
            ctx.response().putHeader("Content-Type", PROMETHEUS_CONTENT_TYPE)
            ctx.response().isChunked = true
            val ch = ctx.response().toChannel(vx)
            try {
                val metricWriter = PrometheusMetricWriter(ch)
                collectors.forEach { collector ->
                    try {
                        collector.export(metricWriter)
                    } catch (ex: Exception) {
                        log.warn("$endpoint[${collector.instance}]: fail: $ex")
                    }
                }
            } finally {
                ch.close()
            }
        }
    }

    router.get("/").coroutineHandler { ctx ->
        val endpointsSorted = endpoints.toMutableList().also { it.sort() }
        val response = buildString {
            append("<!DOCTYPE html><html><head><title>Universal Exporter</title>")
            append("<body><h1>Universal Exporter</h1>")
            for (endpoint in endpointsSorted) {
                append("<p><a href=\"$endpoint\">$endpoint</a></p>")
            }
            append("</body></html>")
        }
        ctx.response().putHeader("Content-Type", "text/html; charset=UTF-8")
        ctx.response().end(response)
    }

    GlobalScope.launch(vx.dispatcher()) {
        val httpServer = awaitResult<HttpServer> { handler ->
            vx.createHttpServer()
                    .requestHandler(router)
                    .listen(config.http?.port ?: 8080, handler)
        }

        log.info("listening on port ${httpServer.actualPort()}")
    }
}

fun main(args: Array<String>) = UniversalExporter().main(args)
