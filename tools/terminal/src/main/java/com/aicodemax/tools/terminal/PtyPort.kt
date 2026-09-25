package com.aicodemax.tools.terminal

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome

/**
 * CP-32 managed PTY (§27): the app's own interactive shell runtime —
 * no Termux install needed. The native lib (`libaicode_pty.so`, arm64)
 * is packed by CI; JVM/desktop gets an honest unavailable signal.
 */
object PtyJni {
    val available: Boolean

    init {
        var ok = false
        try {
            System.loadLibrary("aicode_pty")
            ok = true
        } catch (_: Throwable) {
            ok = false
        }
        available = ok
    }

    external fun ptyOpen(shell: String, cols: Int, rows: Int): Int

    /** Null = timeout (no data yet); empty array = EOF (shell exited). */
    external fun ptyRead(handle: Int, maxBytes: Int, timeoutMs: Int): ByteArray?

    external fun ptyWrite(handle: Int, data: ByteArray): Int
    external fun ptyResize(handle: Int, cols: Int, rows: Int): Int
    external fun ptyClose(handle: Int)
}

data class PtyHandle(val id: Int)

interface PtyPort {
    val available: Boolean
    val unavailableReason: String
    fun open(cols: Int = 80, rows: Int = 24): Outcome<PtyHandle>

    /** Null data = read timeout; empty data = shell exited (EOF). */
    fun read(handle: PtyHandle, timeoutMs: Int = 500): Outcome<ByteArray?>
    fun write(handle: PtyHandle, data: ByteArray): Outcome<Int>
    fun resize(handle: PtyHandle, cols: Int, rows: Int): Outcome<Unit>
    fun close(handle: PtyHandle)
}

/** JNI-backed PTY (Android arm64 with CI-packed .so). */
class JniPtyPort(private val shell: String = "/system/bin/sh") : PtyPort {
    override val available: Boolean get() = PtyJni.available
    override val unavailableReason: String =
        "native PTY ยังไม่ถูกแพ็กในเครื่องนี้ (ต้องใช้ APK arm64 ที่ CI บิลด์)"

    override fun open(cols: Int, rows: Int): Outcome<PtyHandle> {
        if (!available) return Outcome.Failure(AppError("PTY_NATIVE_MISSING", unavailableReason))
        if (cols !in 20..500 || rows !in 5..200) {
            return Outcome.Failure(AppError("PTY_BAD_SIZE", "ขนาดจอต้องอยู่ระหว่าง 20-500 คอลัมน์, 5-200 แถว"))
        }
        val handle = PtyJni.ptyOpen(shell, cols, rows)
        if (handle <= 0) {
            return Outcome.Failure(AppError("PTY_OPEN", "เปิด PTY ไม่ได้ (errno ${-handle}): ตรวจว่า $shell รันได้"))
        }
        return Outcome.Success(PtyHandle(handle))
    }

    override fun read(handle: PtyHandle, timeoutMs: Int): Outcome<ByteArray?> {
        if (!available) return Outcome.Failure(AppError("PTY_NATIVE_MISSING", unavailableReason))
        return try {
            Outcome.Success(PtyJni.ptyRead(handle.id, 4096, timeoutMs.coerceIn(0, 10_000)))
        } catch (e: Exception) {
            Outcome.Failure(AppError("PTY_READ", "อ่าน PTY ไม่ได้: ${e.message}"))
        }
    }

    override fun write(handle: PtyHandle, data: ByteArray): Outcome<Int> {
        if (!available) return Outcome.Failure(AppError("PTY_NATIVE_MISSING", unavailableReason))
        val n = PtyJni.ptyWrite(handle.id, data)
        if (n < 0) return Outcome.Failure(AppError("PTY_WRITE", "เขียน PTY ไม่ได้ (errno ${-n})"))
        return Outcome.Success(n)
    }

    override fun resize(handle: PtyHandle, cols: Int, rows: Int): Outcome<Unit> {
        if (!available) return Outcome.Failure(AppError("PTY_NATIVE_MISSING", unavailableReason))
        if (cols !in 20..500 || rows !in 5..200) {
            return Outcome.Failure(AppError("PTY_BAD_SIZE", "ขนาดจอต้องอยู่ระหว่าง 20-500 คอลัมน์, 5-200 แถว"))
        }
        val rc = PtyJni.ptyResize(handle.id, cols, rows)
        if (rc != 0) return Outcome.Failure(AppError("PTY_RESIZE", "ปรับขนาด PTY ไม่ได้ (errno ${-rc})"))
        return Outcome.Success(Unit)
    }

    override fun close(handle: PtyHandle) {
        if (available) {
            try {
                PtyJni.ptyClose(handle.id)
            } catch (_: Exception) {
            }
        }
    }
}
