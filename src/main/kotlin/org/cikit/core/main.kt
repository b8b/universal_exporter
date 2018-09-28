package org.cikit.core

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpServer
import io.vertx.ext.web.Route
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.core.http.HttpServerOptions
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.awaitResult
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.withTimeout
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.*
import java.util.concurrent.TimeUnit

fun Route.coroutineHandler(timeout: Long? = 10000L, fn: suspend (RoutingContext) -> Unit) {
    handler { ctx ->
        val job = launch(ctx.vertx().dispatcher()) {
            try {
                if (timeout == null) {
                    fn(ctx)
                } else {
                    withTimeout(timeout, TimeUnit.MILLISECONDS) {
                        fn(ctx)
                    }
                }
            } catch (e: Exception) {
                ctx.fail(e)
            }
        }
        ctx.request().connection().closeHandler {
            job.cancel(RuntimeException("client disconnected"))
        }
    }
}

class Verticle(private val fileConfig: Config) : CoroutineVerticle() {

    override suspend fun start() {
        val router = Router.router(vertx)
        val endpoints = mutableSetOf<String>()

        ServiceLoader.load(Module::class.java).forEach { module ->
            val collectorFactory = module.collectorFactory
            if (collectorFactory != null)
                collector(router, module.name) { name, instanceCfg ->
                    endpoints.add(name)
                    collectorFactory(vertx, instanceCfg)
                }
        }

        router.get("/").coroutineHandler { ctx ->
            index(ctx, endpoints.asSequence().sorted().toList())
        }

        awaitResult<HttpServer> {
            vertx.createHttpServer(HttpServerOptions(idleTimeout = 10))
                    .requestHandler(router::accept)
                    .listen(fileConfig.getInt("http.port"), it)
        }
    }

    private fun index(ctx: RoutingContext, endpoints: List<String>) {
        ByteArrayOutputStream().use { out ->
            out.writer().use { writer ->
                writer.write("<!DOCTYPE html>\n")
                writer.appendIndex(endpoints.map { "metrics/$it" })
            }
            ctx.response().putHeader("Content-Type", "text/html; charset=UTF-8")
            ctx.response().end(Buffer.buffer(out.toByteArray()))
        }
    }

    fun <C : Collector> collector(router: Router, name: String, collectorSupplier: (name: String, instanceCfg: Config) -> C): List<C> {
        if (fileConfig.hasPath(name)) {
            val exporters = fileConfig.getConfigList(name).map { instanceConfig ->
                collectorSupplier(name, instanceConfig)
            }
            router.get("/metrics/$name").coroutineHandler { ctx ->
                ctx.response().putHeader("Content-Type", PROMETHEUS_CONTENT_TYPE)
                ctx.response().isChunked = true
                val ch = ctx.response().toByteChannel(vertx)
                try {
                    val metricWriter = PrometheusMetricWriter(ch)
                    exporters.forEach { it.export(metricWriter) }
                } finally {
                    ch.close()
                }
            }
            return exporters
        } else {
            return emptyList()
        }
    }

}

fun main(args: Array<String>) {
    System.setProperty(
            io.vertx.core.logging.LoggerFactory.LOGGER_DELEGATE_FACTORY_CLASS_NAME,
            io.vertx.core.logging.SLF4JLogDelegateFactory::class.java.name)
    LoggerFactory.getLogger(io.vertx.core.logging.LoggerFactory::class.java)

    val log = LoggerFactory.getLogger("main")

    val argsMap = args.map { it.substringBefore('=') to it.substringAfter('=', "") }.toMap()
    val configFile = argsMap["-config"]?.let { File(it) }
    val fileConfig = configFile?.let { ConfigFactory.parseFile(it) }
            ?: ConfigFactory.load()
    val defaultConfig = ConfigFactory.defaultReference()
    val config = fileConfig.withFallback(defaultConfig)

    configureLogging(config)

    val vertxOptions = VertxOptions()
            .setBlockedThreadCheckInterval(10000)
            .setEventLoopPoolSize(1)

    val vertx = Vertx.vertx(vertxOptions)

    vertx.deployVerticle(Verticle(config)) { ar ->
        if (ar.succeeded()) {
            log.info("Application started")
        } else {
            log.error("Could not start application", ar.cause())
            System.exit(1)
        }
    }
}
