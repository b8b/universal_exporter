package test.syslog

import kotlinx.coroutines.experimental.Unconfined
import kotlinx.coroutines.experimental.channels.ArrayChannel
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.runBlocking
import org.cikit.modules.syslog.rfc5424.Progressive2
import org.junit.Assert
import org.junit.Test
import java.nio.ByteBuffer
import java.time.OffsetDateTime

class ParserTest {

    @Test
    fun testWithoutSd() {
        val record = "<123>1 2000-01-01T00:00:00+00:00 localhost app 0 - - hello"
        val ch = ArrayChannel<ByteBuffer>(1)
        launch(Unconfined) {
            ch.send(ByteBuffer.wrap(record.toByteArray()))
            ch.close()
        }
        val p = Progressive2(ch)
        runBlocking {
            Assert.assertTrue(p.parse5424())
            Assert.assertEquals(OffsetDateTime.parse("2000-01-01T00:00:00+00:00"), p.ts())
            Assert.assertEquals(null, p.sd("meta", "sequenceId"))
            val msg = Charsets.UTF_8.decode(p.readMessageFully()).toString()
            Assert.assertEquals("hello", msg)
        }
    }
}