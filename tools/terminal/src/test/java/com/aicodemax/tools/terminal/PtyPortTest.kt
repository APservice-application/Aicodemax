package com.aicodemax.tools.terminal

import com.aicodemax.core.common.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PtyPortTest {
    /** Loopback fake: written bytes come back on read, then EOF after close. */
    private class FakePtyPort : PtyPort {
        override val available = true
        override val unavailableReason = ""
        private var nextId = 1
        private val buffers = mutableMapOf<Int, ByteArray>()
        private val sizes = mutableMapOf<Int, Pair<Int, Int>>()

        override fun open(cols: Int, rows: Int): Outcome<PtyHandle> {
            if (cols !in 20..500 || rows !in 5..200) {
                return Outcome.Failure(com.aicodemax.core.common.AppError("PTY_BAD_SIZE", "bad"))
            }
            val id = nextId++
            buffers[id] = ByteArray(0)
            sizes[id] = cols to rows
            return Outcome.Success(PtyHandle(id))
        }

        override fun read(handle: PtyHandle, timeoutMs: Int): Outcome<ByteArray?> {
            val buf = buffers[handle.id]
                ?: return Outcome.Failure(com.aicodemax.core.common.AppError("PTY_READ", "closed"))
            if (buf.isEmpty()) return Outcome.Success(null) // nothing yet: timeout
            buffers[handle.id] = ByteArray(0)
            return Outcome.Success(buf)
        }

        override fun write(handle: PtyHandle, data: ByteArray): Outcome<Int> {
            if (!buffers.containsKey(handle.id)) {
                return Outcome.Failure(com.aicodemax.core.common.AppError("PTY_WRITE", "closed"))
            }
            buffers[handle.id] = data
            return Outcome.Success(data.size)
        }

        override fun resize(handle: PtyHandle, cols: Int, rows: Int): Outcome<Unit> {
            if (!buffers.containsKey(handle.id)) {
                return Outcome.Failure(com.aicodemax.core.common.AppError("PTY_RESIZE", "closed"))
            }
            sizes[handle.id] = cols to rows
            return Outcome.Success(Unit)
        }

        override fun close(handle: PtyHandle) {
            buffers.remove(handle.id)
        }

        fun sizeOf(handle: PtyHandle): Pair<Int, Int>? = sizes[handle.id]
    }

    @Test
    fun loopbackLifecycle() {
        val port = FakePtyPort()
        val handle = (port.open(80, 24) as Outcome.Success<PtyHandle>).value
        assertTrue((port.read(handle, 10) as Outcome.Success).value == null) // idle: timeout
        port.write(handle, "hi\n".toByteArray())
        val back = (port.read(handle, 10) as Outcome.Success).value
        assertEquals("hi\n", back!!.toString(Charsets.UTF_8))
        assertTrue(port.resize(handle, 100, 30) is Outcome.Success)
        assertEquals(100 to 30, port.sizeOf(handle))
        port.close(handle)
        assertTrue(port.read(handle, 10) is Outcome.Failure)
    }

    @Test
    fun badSizeRejected() {
        val port = FakePtyPort()
        assertTrue(port.open(10, 24) is Outcome.Failure)
        assertTrue(port.open(80, 300) is Outcome.Failure)
    }

    @Test
    fun jniPortIsHonestWithoutNativeLib() {
        // JVM CI has no libaicode_pty.so: must report unavailable, never crash.
        val port = JniPtyPort()
        if (!port.available) {
            val res = port.open(80, 24)
            assertTrue(res is Outcome.Failure)
            assertTrue((res as Outcome.Failure).error.message.contains("arm64"))
        } else {
            // Native present (device runs): open/close must not throw.
            when (val res = port.open(80, 24)) {
                is Outcome.Success -> port.close(res.value)
                is Outcome.Failure -> assertTrue(res.error.code.startsWith("PTY_"))
            }
        }
    }
}
