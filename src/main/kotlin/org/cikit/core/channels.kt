package org.cikit.core

import io.netty.buffer.Unpooled
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.streams.ReadStream
import io.vertx.core.streams.WriteStream
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.experimental.channels.ArrayChannel
import kotlinx.coroutines.experimental.channels.ReceiveChannel
import kotlinx.coroutines.experimental.io.ByteChannel
import kotlinx.coroutines.experimental.io.ByteReadChannel
import kotlinx.coroutines.experimental.io.ByteWriteChannel
import kotlinx.coroutines.experimental.launch
import java.nio.ByteBuffer

fun ReadStream<Buffer>.toByteChannel(vertx: Vertx): ByteReadChannel {
    return VertxByteReadChannel(vertx, this)
}

private class VertxByteReadChannel private constructor(
        private val vertx: Vertx,
        private val readStream: ReadStream<Buffer>,
        private val readChannel: ByteChannel) : ByteReadChannel by readChannel {

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
                try {
                    val byteBuf = event.byteBuf
                    while (byteBuf.isReadable) {
                        readChannel.write(2) { dst ->
                            val wantToWrite = byteBuf.writerIndex() - byteBuf.readerIndex()
                            val origLimit = dst.limit()
                            if (dst.remaining() > wantToWrite) {
                                dst.limit(dst.position() + wantToWrite)
                            } else if (dst.remaining() > 1) {
                                //workaround flush bug
                                dst.limit(origLimit - 1)
                            }
                            byteBuf.readBytes(dst)
                            dst.limit(origLimit)
                        }
                        readChannel.flush()
                    }
                } catch (ex: Throwable) {
                    readChannel.close(ex)
                }
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

fun ReadStream<Buffer>.toReceiveChannel(vertx: Vertx): ReceiveChannel<ByteBuffer> {
    return VertxReceiveChannel(vertx, this)
}

private class VertxReceiveChannel(
        private val vertx: Vertx,
        private val readStream: ReadStream<Buffer>) : ArrayChannel<ByteBuffer>(1) {

    init {
        readStream.endHandler { _ ->
            close()
        }
        readStream.exceptionHandler { err ->
            close(err)
        }
        readStream.handler { event ->
            val job = launch(vertx.dispatcher()) {
                try {
                    send(event.byteBuf.nioBuffer())
                } catch (ex: Throwable) {
                    close(ex)
                }
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

private class VertxByteWriteChannel private constructor(
        private val vertx: Vertx,
        private val writeStream: WriteStream<Buffer>,
        private val writeChannel: ByteChannel) : ByteWriteChannel by writeChannel {

    constructor(vertx: Vertx, writeStream: WriteStream<Buffer>) : this(vertx, writeStream, ByteChannel())

    fun pump(): VertxByteWriteChannel {
        launch(vertx.dispatcher()) {
            try {
                val buffer = ByteArray(1024 * 4)
                while (!writeStream.writeQueueFull()) {
                    val read = writeChannel.readAvailable(buffer)
                    if (read < 0) {
                        writeStream.end()
                        return@launch
                    }
                    writeStream.write(Buffer.buffer(Unpooled.wrappedBuffer(buffer, 0, read)))
                }
                writeStream.drainHandler {
                    pump()
                }
            } catch (ex: Throwable) {
                writeChannel.close(ex)
            }
        }
        return this
    }
}
