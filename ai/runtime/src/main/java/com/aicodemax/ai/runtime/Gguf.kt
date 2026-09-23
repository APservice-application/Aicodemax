package com.aicodemax.ai.runtime

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * CP-125: minimal GGUF metadata reader (spec §5/§24 — arch/quant checks before
 * load). Reads only the KV section; never touches tensor data.
 */
object Gguf {
    data class Info(
        val version: Long,
        val architecture: String?,
        val quantizationVersion: Long?,
        val name: String?,
        val tensorCount: Long,
    )

    private const val TYPE_UINT8 = 0
    private const val TYPE_INT8 = 1
    private const val TYPE_UINT16 = 2
    private const val TYPE_INT16 = 3
    private const val TYPE_UINT32 = 4
    private const val TYPE_INT32 = 5
    private const val TYPE_FLOAT32 = 6
    private const val TYPE_BOOL = 7
    private const val TYPE_STRING = 8
    private const val TYPE_ARRAY = 9
    private const val TYPE_UINT64 = 10
    private const val TYPE_INT64 = 11
    private const val TYPE_FLOAT64 = 12

    private class Reader(private val buf: ByteBuffer) {
        fun u8(): Int = buf.get().toInt() and 0xFF
        fun u16(): Int = buf.short.toInt() and 0xFFFF
        fun u32(): Long = buf.int.toLong() and 0xFFFFFFFFL
        fun u64(): Long = buf.long
        fun bytes(n: Int): ByteArray = ByteArray(n).also { buf.get(it) }
        fun string(): String {
            val len = u64()
            if (len < 0 || len > 16 * 1024 * 1024) throw IllegalArgumentException("bad GGUF string len $len")
            return bytes(len.toInt()).toString(Charsets.UTF_8)
        }
        fun remaining(): Int = buf.remaining()
    }

    /** Read [Info] from the file header (reads at most 8MB of KV data). */
    fun read(file: File): Info {
        if (!file.isFile) throw IllegalArgumentException("ไม่พบไฟล์: ${file.path}")
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(10)
            raf.readFully(head)
            val magic = head.copyOfRange(0, 4).toString(Charsets.UTF_8)
            if (magic != "GGUF") throw IllegalArgumentException("ไม่ใช่ไฟล์ GGUF (magic: $magic)")
            val tailLen = minOf(raf.length() - 10, 8 * 1024 * 1024).toInt().coerceAtLeast(0)
            val rest = ByteArray(tailLen)
            if (tailLen > 0) raf.readFully(rest)
            val full = head + rest
            val r = Reader(ByteBuffer.wrap(full).order(ByteOrder.LITTLE_ENDIAN))
            r.bytes(4) // magic
            val version = r.u32()
            val nTensors = r.u64()
            val nKv = r.u64()
            if (nKv < 0 || nKv > 100_000) throw IllegalArgumentException("bad GGUF kv count $nKv")
            var arch: String? = null
            var quant: Long? = null
            var name: String? = null
            repeat(nKv.toInt()) {
                if (r.remaining() <= 0) throw IllegalArgumentException("GGUF ถูกตัด (KV เกินข้อมูล)")
                val key = r.string()
                val type = r.u32().toInt()
                when (key) {
                    "general.architecture" -> arch = readTypedString(r, type)
                    "general.name" -> name = readTypedString(r, type)
                    "general.quantization_version" -> quant = readTypedUint(r, type)
                    else -> skipValue(r, type)
                }
            }
            return Info(version, arch, quant, name, nTensors)
        }
    }

    private fun readTypedString(r: Reader, type: Int): String? = when (type) {
        TYPE_STRING -> r.string()
        else -> {
            skipValue(r, type)
            null
        }
    }

    private fun readTypedUint(r: Reader, type: Int): Long? = when (type) {
        TYPE_UINT8 -> r.u8().toLong()
        TYPE_UINT16 -> r.u16().toLong()
        TYPE_UINT32 -> r.u32()
        TYPE_UINT64 -> r.u64()
        else -> {
            skipValue(r, type)
            null
        }
    }

    private fun skipValue(r: Reader, type: Int) {
        when (type) {
            TYPE_UINT8, TYPE_INT8, TYPE_BOOL -> r.u8()
            TYPE_UINT16, TYPE_INT16 -> r.u16()
            TYPE_UINT32, TYPE_INT32, TYPE_FLOAT32 -> r.u32()
            TYPE_UINT64, TYPE_INT64, TYPE_FLOAT64 -> r.u64()
            TYPE_STRING -> r.string()
            TYPE_ARRAY -> {
                val elem = r.u32().toInt()
                val len = r.u64()
                if (len < 0 || len > 10_000_000) throw IllegalArgumentException("bad GGUF array len $len")
                repeat(len.toInt()) { skipValue(r, elem) }
            }
            else -> throw IllegalArgumentException("unknown GGUF type $type")
        }
    }
}
