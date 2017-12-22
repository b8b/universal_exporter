package exporter

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import exporter.ganglia.GangliaExporter
import exporter.haproxy.HAProxyExporter
import exporter.rabbitmq.RabbitMQExporter
import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpServer
import io.vertx.core.streams.ReadStream
import io.vertx.core.streams.WriteStream
import io.vertx.ext.web.Route
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.core.http.HttpServerOptions
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.awaitResult
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.experimental.cancel
import kotlinx.coroutines.experimental.io.ByteChannel
import kotlinx.coroutines.experimental.io.ByteReadChannel
import kotlinx.coroutines.experimental.io.ByteWriteChannel
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

fun Route.coroutineHandler(timeout: Long? = 10000L, fn : suspend (RoutingContext) -> Unit) {
    handler { ctx ->
        launch(ctx.vertx().dispatcher()) {
            ctx.request().connection().closeHandler {
                this.coroutineContext.cancel(RuntimeException("client disconnected"))
            }
            try {
                if (timeout == null) {
                    fn(ctx)
                } else {
                    withTimeout(timeout, TimeUnit.MILLISECONDS) {
                        fn(ctx)
                    }
                }
            } catch(e: Exception) {
                ctx.fail(e)
            }
        }
    }
}

fun ReadStream<Buffer>.toByteChannel(vertx: Vertx): ByteReadChannel {
    return VertxByteReadChannel(vertx, this)
}

private class VertxByteReadChannel private constructor(
        private val vertx: Vertx,
        private val readStream: ReadStream<Buffer>,
        private val readChannel: ByteChannel): ByteReadChannel by readChannel {

    constructor(vertx: Vertx, readStream: ReadStream<Buffer>) : this(vertx, readStream, ByteChannel())

    init {
        readStream.endHandler { _ ->
            readChannel.close()
        }
        readStream.exceptionHandler { err ->
            readChannel.close(err)
        }
        readStream.handler { event ->
            val job = launch(vertx.dispatcher()) {
                val byteBuf = event.byteBuf
                if (byteBuf.hasArray()) {
                    readChannel.writeFully(byteBuf.array(), byteBuf.arrayOffset(), byteBuf.writerIndex())
                } else {
                    readChannel.writeFully(event.bytes)
                }
                readChannel.flush()
            }
            if (job.isActive) {
                readStream.pause()
                job.invokeOnCompletion {
                    readStream.resume()
                }
            }
        }
    }
}

fun WriteStream<Buffer>.toByteChannel(vertx: Vertx): ByteWriteChannel {
    return VertxByteWriteChannel(vertx, this).pump()
}

private class VertxByteWriteChannel private constructor(
        private val vertx: Vertx,
        private val writeStream: WriteStream<Buffer>,
        private val writeChannel: ByteChannel) : ByteWriteChannel by writeChannel {

    constructor(vertx: Vertx, writeStream: WriteStream<Buffer>) : this(vertx, writeStream, ByteChannel())

    fun pump(): VertxByteWriteChannel {
        launch(vertx.dispatcher()) {
            val buffer = ByteArray(1024 * 4)
            while (!writeStream.writeQueueFull()) {
                val read = writeChannel.readAvailable(buffer)
                if (read < 0) {
                    writeStream.end()
                    return@launch
                }
                writeStream.write(Buffer.buffer(buffer).slice(0, read))
            }
            writeStream.drainHandler {
                pump()
            }
        }
        return this
    }
}

class Verticle(private val fileConfig: Config) : CoroutineVerticle() {

    suspend override fun start() {
        val router = Router.router(vertx)
        val endpoints = mutableSetOf<String>()

        exporter(router, "haproxy") { name, instanceCfg ->
            endpoints.add(name)
            HAProxyExporter(vertx, instanceCfg)
        }

        exporter(router, "rabbitmq") { name, instanceCfg ->
            endpoints.add(name)
            RabbitMQExporter(vertx, instanceCfg)
        }

        exporter(router, "ganglia") { name, instanceCfg ->
            endpoints.add(name)
            GangliaExporter(vertx, instanceCfg)
        }

        router.get("/").coroutineHandler { ctx ->
            index(ctx, endpoints.toList().sorted())
        }

        awaitResult<HttpServer> {
            HttpServerOptions(idleTimeout = 10)
            vertx.createHttpServer()
                .requestHandler(router::accept)
                .listen(config.getInteger("http.port", 8080), it)
        }
    }

    private suspend fun index(ctx: RoutingContext, endpoints: List<String>) {
        ByteArrayOutputStream().use {
            it.writer().use { out ->
                out.write("<!DOCTYPE html>\n")
                out.appendIndex(endpoints.map { "metrics/$it" })
            }
            ctx.response().putHeader("Content-Type", "text/html; charset=UTF-8")
            ctx.response().end(Buffer.buffer(it.toByteArray()))
        }
    }

    fun exporter(router: Router, name: String, exporterSupplier: (name: String, instanceCfg: Config) -> Exporter) {
        if (fileConfig.hasPath(name)) {
            val exporters = fileConfig.getConfigList(name).map { instanceConfig ->
                exporterSupplier(name, instanceConfig)
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
        }
    }

}

fun main(args : Array<String>) {
    val argsMap = args.map { it.substringBefore('=') to it.substringAfter('=', "") }.toMap()
    val configFile = argsMap["-config"]?.let { File(it) }
    val fileConfig = configFile?.let { ConfigFactory.parseFile(it) } ?: ConfigFactory.load()

    val vertxOptions = VertxOptions()
            .setBlockedThreadCheckInterval(10000)
            .setEventLoopPoolSize(1)

    val vertx = Vertx.vertx(vertxOptions)

    vertx.deployVerticle(Verticle(fileConfig)) { ar ->
        if (ar.succeeded()) {
            println("Application started")
        } else {
            println("Could not start application")
            ar.cause().printStackTrace()
        }
    }
}