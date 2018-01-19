import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import io.vertx.core.Vertx
import io.vertx.ext.unit.TestContext
import io.vertx.ext.unit.junit.VertxUnitRunner
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.net.Socket
import java.net.SocketException

@RunWith(VertxUnitRunner::class)
class SyslogParserTest {

    lateinit var vertx: Vertx

    @Before
    fun setup(context: TestContext) {
        val lc = LoggerFactory.getILoggerFactory() as LoggerContext
        val rootLogger = lc.getLogger(Logger.ROOT_LOGGER_NAME)
        rootLogger.level = Level.INFO
        vertx = Vertx.vertx()
//        vertx.deployVerticle(SyslogCollector(), context.asyncAssertSuccess())
    }

    @After
    fun tearDown(context: TestContext) {
        vertx.close(context.asyncAssertSuccess())
    }

    @Test
    fun stressTest(context: TestContext) {
        val startTime = System.nanoTime()
        val sock = Socket("localhost", 8088)
        val out = sock.getOutputStream().buffered()
        for (i in 1 .. 1000000) {
            out.write("<134>1 2018-01-04T16:50:40.342+01:00 myhost myapp 3438 - [meta sequenceId=\"$i\"] {}\n".toByteArray())
        }
        out.flush()
        sock.shutdownOutput()
        try {
            val input = sock.getInputStream()
            while (input.read() > 0) {
            }
        } catch (ex: SocketException) {
            ex.printStackTrace()
        }
        val endTime = System.nanoTime()
        println("done in ${BigDecimal(endTime - startTime).movePointLeft(9)} second(s)")
    }
}