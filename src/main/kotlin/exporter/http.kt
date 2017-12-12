package exporter

import kotlinx.coroutines.experimental.io.ByteBuffer
import kotlinx.coroutines.experimental.io.ByteReadChannel
import kotlinx.coroutines.experimental.io.readUntilDelimiter
import kotlinx.coroutines.experimental.io.skipDelimiter

suspend fun readHeaders(readChannel: ByteReadChannel) {
    val delimiter = ByteBuffer.wrap("\r\n".toByteArray())
    val buffer = ByteArray(1024 * 4)
    while (true) {
        val read = readChannel.readUntilDelimiter(delimiter, ByteBuffer.wrap(buffer))
        if (read < buffer.size) {
            readChannel.skipDelimiter(delimiter)
            if (read == 0) {
                break
            }
        } else {
            throw IllegalStateException()
        }
    }
}
