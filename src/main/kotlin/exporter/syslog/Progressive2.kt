package exporter.syslog

import kotlinx.coroutines.experimental.channels.ReceiveChannel
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.time.OffsetDateTime

class Progressive2(val channel: ReceiveChannel<ByteBuffer>) {

    private val _tmp = ByteBuffer.allocateDirect(1024 * 2)
    private var tmp = _tmp

    private var current = ByteBuffer.allocate(0)
    private var dup: ByteBuffer? = null
    private var startIndex: Int = 0
    private var endIndex: Int = 0

    private val nilBytes = ByteBuffer.wrap(byteArrayOf('-'.toByte())).asReadOnlyBuffer()
    private var cachedHost: Pair<ByteBuffer, String?> = nilBytes to null
    private var cachedApp: Pair<ByteBuffer, String?> = nilBytes to null

    private val keys = mutableMapOf<ByteBuffer, String>()
    private val values = mutableMapOf<Pair<String, String>, String>()

    private var priValue = -1
    private var ts: OffsetDateTime? = null
    private var proc: Long? = null
    private var msgid: String? = null

    fun priValue() = priValue
    fun facility() = if (priValue < 0) null else Facility.values().getOrNull(priValue shr 3)
    fun severity() = if (priValue < 0) null else Severity.values().getOrNull(priValue and 0x7)
    fun ts() = ts
    fun host() = cachedHost.second
    fun app() = cachedApp.second
    fun proc() = proc
    fun msgid() = msgid
    fun sd(id: String, key: String): String? = values[id to key]

    private suspend fun receive(): Boolean {
        current = channel.receiveOrNull() ?: return false
        dup = null
        startIndex = current.position()
        endIndex = current.remaining()
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

    private suspend fun readUntil(predicate: (Byte) -> Boolean = ::isSpace): Boolean {
        val currentDup = dup ?: current.duplicate()
        if (dup == null) dup = currentDup
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
        _tmp.clear()
        _tmp.put(currentDup)
        tmp = _tmp
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
        val host: String? = let {
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
        val app: String? = let {
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

    suspend fun readMessage(): String? {
        if (!readUntil({ it == '\n'.toByte() })) return null
        skipByte()
        return tmpChars().toString()
    }

    suspend fun skipMessage() {
        skip({ it != '\n'.toByte() })
        skipByte()
    }
}
