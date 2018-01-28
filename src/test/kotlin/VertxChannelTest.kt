import org.cikit.core.coroutineHandler
import org.cikit.core.readHeaders
import org.cikit.core.toByteChannel
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpServer
import io.vertx.core.net.NetSocket
import io.vertx.core.streams.ReadStream
import io.vertx.core.streams.WriteStream
import io.vertx.ext.unit.TestContext
import io.vertx.ext.unit.junit.VertxUnitRunner
import io.vertx.ext.web.Router
import io.vertx.kotlin.core.http.HttpServerOptions
import io.vertx.kotlin.core.net.NetClientOptions
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.awaitResult
import io.vertx.kotlin.coroutines.toChannel
import kotlinx.coroutines.experimental.io.readUTF8Line
import kotlinx.coroutines.experimental.io.writeStringUtf8
import kotlinx.coroutines.experimental.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith


@RunWith(VertxUnitRunner::class)
class VertxChannelTest {

    companion object {
        val port = 8081
    }

    lateinit var vertx: Vertx

    class TestVerticle : CoroutineVerticle() {
        suspend override fun start() {
            val router = Router.router(vertx )
            router.get("/").coroutineHandler { ctx ->
                ctx.response().putHeader("Content-Type", "text/plain")
                ctx.response().isChunked = true
                val ch = ctx.response().toByteChannel(vertx)
                try {
                    for (i in 0..100000) {
                        ch.writeStringUtf8("hello\n")
                    }
                } finally {
                    println("cleaning up")
                    ch.close()
                }
            }
            awaitResult<HttpServer> {
                vertx.createHttpServer(HttpServerOptions(idleTimeout = 10))
                        .requestHandler(router::accept)
                        .listen(port, it)
            }
        }
    }

    @Before
    fun setup(context: TestContext) {
        vertx = Vertx.vertx()
        vertx.deployVerticle(TestVerticle(), context.asyncAssertSuccess())
    }

    @After
    fun tearDown(context: TestContext) {
        vertx.close(context.asyncAssertSuccess())
    }

    @Test
    fun runTestClient(context: TestContext) {
        val client = vertx.createNetClient(NetClientOptions(connectTimeout = 3000))
        runBlocking {
            val socket = awaitResult<NetSocket> {
                client.connect(port, "localhost", it)
            }
            val sendChannel = (socket as WriteStream<Buffer>).toChannel(vertx)
            sendChannel.send(Buffer.buffer("GET / HTTP/1.0\r\n\r\n"))

            val readChannel = (socket as ReadStream<Buffer>).toByteChannel(vertx)

            //read http headers
            readHeaders(readChannel)

            var counter = 0
            while (true) {
                val line = readChannel.readUTF8Line() ?: break
                counter += line.length
            }
            println("read $counter chars")

            socket.close()
        }
    }

}
