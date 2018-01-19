package rfc5424

import kotlinx.coroutines.experimental.channels.ReceiveChannel
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.time.OffsetDateTime

class Progressive2(val channel: ReceiveChannel<ByteBuffer>,
                   val internalBuffer: ByteBuffer = ByteBuffer.allocateDirect(1024 * 1024 * 64)) {

    private var tmp = internalBuffer

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

    private suspend fun receive(): Boolean {
        bytesConsumedTotal += endIndex - firstStartIndex
        current = channel.receiveOrNull() ?: return false
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
        while (true) {
            for (i in startIndex until endIndex) {
                val b = current[i]
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
        if (startIndex >= endIndex && !receive()) return false
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

    private suspend fun skipByte(): Boolean {
        while (startIndex >= endIndex) {
            if (!receive()) return false
        }
        startIndex++
        return true
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

    private fun tmpBytes(): ByteBuffer {
        val b = ByteBuffer.allocate(tmp.remaining())
        val origPosition = tmp.position()
        b.put(tmp)
        tmp.position(origPosition)
        b.flip()
        return b.asReadOnlyBuffer()
    }

    private fun tmpChars(cs: Charset = Charsets.UTF_8): CharSequence {
        val origPosition = tmp.position()
        val result = cs.decode(tmp)
        tmp.position(origPosition)
        return result
    }

    suspend fun parse5424(): Boolean {
        //read pri
        priValue = 0
        if (!skipPrefix('<'.toByte())) return false
        if (!readDigits({ priValue = priValue * 10 + it })) return false
        if (!skipPrefix('>'.toByte())) return false

        //read version
        if (!skipPrefix('1'.toByte())) return false
        if (!skip()) return false

        //read ts
        ts = let {
            if (!readUntil()) return false
            if (tmp == nilBytes)
                null
            else
                OffsetDateTime.parse(tmpChars(Charsets.US_ASCII))
        }
        skip()

        //read host
        host = let {
            if (!readUntil()) return false
            when (tmp) {
                cachedHost.first -> cachedHost.second
                nilBytes -> null
                else -> {
                    cachedHost = tmpBytes() to tmpChars().toString()
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
                    cachedApp = tmpBytes() to tmpChars().toString()
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
                tmpChars().toString()
        }
        skip()

        //read sd
        values.clear()
        while (skipPrefix('['.toByte())) {
            //read id
            if (!readUntil()) return false
            val id: String = keys[tmp] ?: tmpChars().toString().also {
                keys.put(tmpBytes(), it)
            }
            skip()
            //field kv pairs
            while (true) {
                if (skipPrefix(']'.toByte())) break
                if (!readUntil({ it == '='.toByte() || it == ']'.toByte() })) return false
                val key: String = keys[tmp] ?: tmpChars().toString().also {
                    keys.put(tmpBytes(), it)
                }
                if (skipPrefix('='.toByte())) {
                    val value = StringBuilder()
                    if (skipPrefix('"'.toByte())) {
                        while (true) {
                            if (!readUntil({ it == '"'.toByte() })) return false
                            skipByte()
                            if (decodeQuotedString(value, tmpChars()))
                                break
                            value.append('"')
                        }
                    } else if (readUntil({ isSpace(it) || it == ']'.toByte() })) {
                        value.append(tmpChars())
                    } else {
                        return false
                    }
                    values.put(id to key, value.toString())
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

    suspend fun readMessageBytes(): ByteBuffer? {
        if (!readUntil({ it == '\n'.toByte() })) return null
        skipByte()
        return tmp
    }

    suspend fun readMessageString(): String? {
        if (!readUntil({ it == '\n'.toByte() })) return null
        skipByte()
        return tmpChars().toString()
    }

    suspend fun skipMessage() {
        skip({ it != '\n'.toByte() })
        skipPrefix('\n'.toByte())
    }
}
