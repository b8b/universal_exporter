package org.cikit.modules.syslog.rfc5424

import kotlinx.coroutines.experimental.channels.ReceiveChannel
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.time.OffsetDateTime
import java.time.ZoneOffset

class Progressive2(val channel: ReceiveChannel<ByteBuffer>,
                   val internalBuffer: ByteBuffer = ByteBuffer.allocateDirect(1024 * 2)) {

    private var tmp = internalBuffer

    private var channelClosed = false
    private var current = ByteBuffer.allocate(0)
    private var dup: ByteBuffer? = null
    private var bytesConsumedTotal: Long = 0
    private var firstStartIndex: Int = 0
    private var startIndex: Int = 0
    private var endIndex: Int = 0

    private val nilBytes = ByteBuffer.wrap(byteArrayOf('-'.toByte())).asReadOnlyBuffer()
    private var cachedHost: Pair<ByteBuffer, String?> = nilBytes to null
    private var cachedApp: Pair<ByteBuffer, String?> = nilBytes to null

    private val keys = mutableMapOf<ByteBuffer, String>()
    private val values = mutableMapOf<Pair<String, String>, String>()

    private var priValue = -1
    private var host: String? = null
    private var app: String? = null
    private var ts: OffsetDateTime? = null
    private var proc: Long? = null
    private var msgid: String? = null

    fun priValue() = priValue
    fun facility() = if (priValue < 0) null else Facility.values().getOrNull(priValue shr 3)
    fun severity() = if (priValue < 0) null else Severity.values().getOrNull(priValue and 0x7)
    fun ts() = ts
    fun host() = host
    fun app() = app
    fun proc() = proc
    fun msgid() = msgid
    fun sd(id: String, key: String): String? = values[id to key]

    fun bytesConsumed() = bytesConsumedTotal + startIndex - firstStartIndex
    fun isClosed() = channelClosed

    private suspend fun receive(): Boolean {
        if (channelClosed) return false
        val new = channel.receiveOrNull()
        if (new == null) {
            channelClosed = true
            return false
        }
        bytesConsumedTotal += endIndex - firstStartIndex
        current = new
        dup = null
        startIndex = current.position()
        endIndex = current.remaining()
        firstStartIndex = startIndex
        return true
    }

    private fun isSpace(b: Byte) = b == ' '.toByte() ||
            b == '\t'.toByte() ||
            b == '\b'.toByte()

    private suspend fun skip(predicate: (Byte) -> Boolean = ::isSpace): Boolean {
        while (true) {
            for (i in startIndex until endIndex) {
                if (!predicate(current[i])) {
                    startIndex = i
                    return true
                }
            }
            if (!receive()) return false
        }
    }

    private suspend fun skipPrefix(b: Byte): Boolean {
        while (startIndex >= endIndex) {
            if (!receive()) return false
        }
        if (current[startIndex] == b) {
            startIndex++
            return true
        }
        return false
    }

    private suspend fun readDigits(block: (Int) -> Unit): Boolean {
        while (startIndex >= endIndex) {
            if (!receive()) return false
        }
        var b = current[startIndex]
        if (b < '0'.toByte() || b > '9'.toByte()) {
            return false
        }
        block(b.toInt() - '0'.toInt())
        startIndex++
        while (true) {
            for (i in startIndex until endIndex) {
                b = current[i]
                if (b < '0'.toByte() || b > '9'.toByte()) {
                    startIndex = i
                    return true
                }
                block(b.toInt() - '0'.toInt())
            }
            if (!receive()) return false
        }
    }

    private suspend fun readUntil(predicate: (Byte) -> Boolean = ::isSpace,
                                  useInternalBuffer: Boolean = true): Boolean {
        if (startIndex >= endIndex && !receive()) {
            val currentDup = dup ?: current.duplicate().also { dup = it }
            currentDup.limit(startIndex)
            currentDup.position(startIndex)
            tmp = currentDup
            return false
        }
        val currentDup = dup ?: current.duplicate().also { dup = it }
        for (i in startIndex until endIndex) {
            val b = current[i]
            if (predicate(b) || b == '\n'.toByte()) {
                currentDup.limit(i)
                currentDup.position(startIndex)
                tmp = currentDup
                startIndex = i
                return true
            }
        }
        currentDup.limit(endIndex)
        currentDup.position(startIndex)
        if (!useInternalBuffer) {
            startIndex = endIndex
            tmp = currentDup
            return false
        }
        internalBuffer.clear()
        internalBuffer.put(currentDup)
        tmp = internalBuffer
        while (receive()) {
            for (i in startIndex until endIndex) {
                val b = current[i]
                if (predicate(b) || b == '\n'.toByte()) {
                    startIndex = i
                    tmp.flip()
                    return true
                }
                tmp.put(b)
            }
        }
        return false
    }

    private suspend fun skipByte(): Int {
        while (startIndex >= endIndex) {
            if (!receive()) return -1
        }
        val b = current[startIndex]
        startIndex++
        return b.toInt()
    }

    private fun decodeQuotedString(sb: StringBuilder, input: CharSequence): Boolean {
        var st = 0
        for (c in input) {
            if (st == 1) {
                if (c != '\\' && c != '"' && c != ']') {
                    sb.append('\\')
                }
                sb.append(c)
                st = 0
                continue
            }
            if (c == '\\') {
                st = 1
                continue
            }
            sb.append(c)
        }
        return st == 0
    }

    private fun copyTmpBytes(): ByteBuffer {
        val b = ByteBuffer.allocate(tmp.remaining())
        val origPosition = tmp.position()
        b.put(tmp)
        tmp.position(origPosition)
        b.flip()
        return b.asReadOnlyBuffer()
    }

    private fun copyTmpChars(cs: Charset = Charsets.UTF_8): CharSequence {
        val origPosition = tmp.position()
        val result = cs.decode(tmp)
        tmp.position(origPosition)
        return result
    }

    private suspend fun parseOffset(sign: Int): ZoneOffset? {
        var hours = 0
        var minutes = 0
        if (!readDigits({ hours = hours * 10 + it })) return null
        if (!skipPrefix(':'.toByte())) return null
        if (!readDigits({ minutes = minutes * 10 + it })) return null
        return ZoneOffset.ofHoursMinutes(hours * sign, minutes * sign)
    }

    private suspend fun parseTs(): Boolean {
        var year = 0
        var month = 0
        var day = 0
        var hour = 0
        var minute = 0
        var second = 0
        var nanos = 0
        if (!readDigits({ year = year * 10 + it })) {
            if (!readUntil()) return false
            if (tmp == nilBytes) {
                ts = null
                return true
            }
            return false
        }
        if (!skipPrefix('-'.toByte())) return false
        if (!readDigits({ month = month * 10 + it })) return false
        if (!skipPrefix('-'.toByte())) return false
        if (!readDigits({ day = day * 10 + it })) return false
        if (!skipPrefix('T'.toByte())) return false
        if (!readDigits({ hour = hour * 10 + it })) return false
        if (!skipPrefix(':'.toByte())) return false
        if (!readDigits({ minute = minute * 10 + it })) return false
        if (!skipPrefix(':'.toByte())) return false
        if (!readDigits({ second = second * 10 + it })) return false
        if (skipPrefix('.'.toByte())) {
            var digits = 0
            if (!readDigits({ digits++; nanos = nanos * 10 + it })) return false
            if (digits > 9) return false
            for (i in digits.inc() .. 9) nanos *= 10
        }
        val z = when (skipByte()) {
            'Z'.toByte().toInt() -> ZoneOffset.UTC
            '+'.toByte().toInt() -> parseOffset(1) ?: return false
            '-'.toByte().toInt() -> parseOffset(-1) ?: return false
            else -> return false
        }
        ts = OffsetDateTime.of(year, month, day, hour, minute, second, nanos, z)
        return true
    }

    suspend fun parse5424(): Boolean {
        //reset
        priValue = 0
        ts = null
        host = null
        app = null
        proc = null
        msgid = null
        values.clear()

        //read pri
        if (!skipPrefix('<'.toByte())) return false
        if (!readDigits({ priValue = priValue * 10 + it })) return false
        if (!skipPrefix('>'.toByte())) return false

        //read version
        if (!skipPrefix('1'.toByte())) return false
        if (!skip()) return false

        //read ts
        parseTs()
        skip()

        //read host
        host = let {
            if (!readUntil()) return false
            when (tmp) {
                cachedHost.first -> cachedHost.second
                nilBytes -> null
                else -> {
                    cachedHost = copyTmpBytes() to copyTmpChars().toString()
                    cachedHost.second
                }
            }
        }
        skip()

        //read app
        app = let {
            if (!readUntil()) return false
            when (tmp) {
                cachedApp.first -> cachedApp.second
                nilBytes -> null
                else -> {
                    cachedApp = copyTmpBytes() to copyTmpChars().toString()
                    cachedApp.second
                }
            }
        }
        skip()

        //read proc
        proc = let {
            var longValue = 0L
            if (readDigits({ longValue = longValue * 10 + it }))
                longValue
            else if (readUntil() && tmp == nilBytes)
                null
            else
                return false
        }
        skip()

        //read msgid
        msgid = let {
            if (!readUntil()) return false
            if (tmp == nilBytes)
                null
            else
                copyTmpChars().toString()
        }
        skip()

        //read sd
        while (skipPrefix('['.toByte())) {
            //read id
            if (!readUntil()) return false
            val id: String = keys[tmp] ?: copyTmpChars().toString().also {
                keys[copyTmpBytes()] = it
            }
            skip()
            //field kv pairs
            while (true) {
                if (skipPrefix(']'.toByte())) break
                if (!readUntil({ it == '='.toByte() || it == ']'.toByte() })) return false
                val key: String = keys[tmp] ?: copyTmpChars().toString().also {
                    keys[copyTmpBytes()] = it
                }
                if (skipPrefix('='.toByte())) {
                    val value = StringBuilder()
                    if (skipPrefix('"'.toByte())) {
                        while (true) {
                            if (!readUntil({ it == '"'.toByte() })) return false
                            skipByte()
                            if (decodeQuotedString(value, copyTmpChars())) break
                            value.append('"')
                        }
                    } else if (readUntil({ isSpace(it) || it == ']'.toByte() })) {
                        value.append(copyTmpChars())
                    } else {
                        return false
                    }
                    values[id to key] = value.toString()
                } else {
                    return false
                }
            }
        }
        skip()

        return true
    }

    suspend fun readMessageAvailable(): ByteBuffer? {
        val result = readUntil({ it == '\n'.toByte()}, false)
        if (tmp.hasRemaining()) return tmp
        if (result) skipByte()
        return null
    }

    suspend fun readMessageFully(): ByteBuffer {
        val result = readUntil({ it == '\n'.toByte() })
        if (result) skipByte()
        return tmp
    }

    suspend fun skipMessage() {
        skip({ it != '\n'.toByte() })
        skipPrefix('\n'.toByte())
    }
}
