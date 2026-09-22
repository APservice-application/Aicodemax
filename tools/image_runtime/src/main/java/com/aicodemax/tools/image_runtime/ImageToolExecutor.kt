package com.aicodemax.tools.image_runtime

import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold
import com.aicodemax.tools.gateway.ToolCall
import com.aicodemax.tools.gateway.ToolExecutor
import com.aicodemax.tools.gateway.ToolResult
import com.aicodemax.tools.image.ImagePort
import com.aicodemax.tools.image.InMemoryImagePort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Gateway executor for image (actions: info/resize/crop/rotate/grayscale). */
class ImageToolExecutor(private val images: ImagePort = InMemoryImagePort()) : ToolExecutor {
    override val toolId: String = "image"

    override suspend fun execute(call: ToolCall): Outcome<ToolResult> =
        withContext(Dispatchers.IO) {
            when (call.action) {
                "info" -> {
                    val path = call.args["path"]
                        ?: return@withContext done(false, error = "missing arg: path")
                    images.info(path).fold(
                        onSuccess = { done(true, "รูป ${it.path}: ${it.summary}") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "resize" -> {
                    val src = call.args["src"] ?: call.args["path"]
                        ?: return@withContext done(false, error = "missing arg: src")
                    val dst = call.args["dst"] ?: defaultDst(src, "small")
                    val maxDim = call.args["maxDim"]?.toIntOrNull() ?: 1024
                    images.resize(src, dst, maxDim).fold(
                        onSuccess = { done(true, "ย่อแล้ว ${it.path}: ${it.summary}") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "crop" -> {
                    val src = call.args["src"] ?: call.args["path"]
                        ?: return@withContext done(false, error = "missing arg: src")
                    val dst = call.args["dst"] ?: defaultDst(src, "crop")
                    val x = call.args["x"]?.toIntOrNull()
                    val y = call.args["y"]?.toIntOrNull()
                    val w = call.args["w"]?.toIntOrNull()
                    val h = call.args["h"]?.toIntOrNull()
                    if (x == null || y == null || w == null || h == null) {
                        return@withContext done(false, error = "missing args: x,y,w,h (เช่น ครอปรูป a.png 10,20,100,100)")
                    }
                    images.crop(src, dst, x, y, w, h).fold(
                        onSuccess = { done(true, "ครอปแล้ว ${it.path}: ${it.summary}") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "rotate" -> {
                    val src = call.args["src"] ?: call.args["path"]
                        ?: return@withContext done(false, error = "missing arg: src")
                    val dst = call.args["dst"] ?: defaultDst(src, "rot")
                    val degrees = call.args["degrees"]?.toIntOrNull() ?: 90
                    images.rotate(src, dst, degrees).fold(
                        onSuccess = { done(true, "หมุนแล้ว ${it.path}: ${it.summary}") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "grayscale" -> {
                    val src = call.args["src"] ?: call.args["path"]
                        ?: return@withContext done(false, error = "missing arg: src")
                    val dst = call.args["dst"] ?: defaultDst(src, "gray")
                    images.grayscale(src, dst).fold(
                        onSuccess = { done(true, "ขาวดำแล้ว ${it.path}: ${it.summary}") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "scopes" -> {
                    val path = call.args["path"] ?: call.args["src"]
                        ?: return@withContext done(false, error = "missing arg: path")
                    images.scopes(path).fold(
                        onSuccess = { done(true, "สโคป $path: ${it.summary()}") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "adjust" -> {
                    val src = call.args["src"] ?: call.args["path"]
                        ?: return@withContext done(false, error = "missing arg: src")
                    val dst = call.args["dst"] ?: defaultDst(src, "adj")
                    images.adjust(
                        src, dst,
                        call.args["brightness"]?.toIntOrNull() ?: 0,
                        call.args["contrast"]?.toIntOrNull() ?: 0,
                        call.args["saturation"]?.toIntOrNull() ?: 0,
                        call.args["sharpness"]?.toIntOrNull() ?: 0,
                    ).fold(
                        onSuccess = { done(true, "แต่งภาพแล้ว ${it.path}: ${it.summary}") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "upscale" -> {
                    val src = call.args["src"] ?: call.args["path"]
                        ?: return@withContext done(false, error = "missing arg: src")
                    val dst = call.args["dst"] ?: defaultDst(src, "big")
                    val scale = call.args["scale"]?.toIntOrNull() ?: 2
                    images.upscale(src, dst, scale).fold(
                        onSuccess = { done(true, "ขยายภาพ ${scale}x แล้ว ${it.path}: ${it.summary} (bicubic — ไม่ใช่ AI super-resolution)") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "restore" -> {
                    val src = call.args["src"] ?: call.args["path"]
                        ?: return@withContext done(false, error = "missing arg: src")
                    val dst = call.args["dst"] ?: defaultDst(src, "fixed")
                    images.restore(
                        src, dst,
                        call.args["denoise"]?.toBooleanStrictOrNull() ?: true,
                        call.args["deFade"]?.toBooleanStrictOrNull() ?: true,
                        call.args["whiteBalance"]?.toBooleanStrictOrNull() ?: true,
                    ).fold(
                        onSuccess = { done(true, "ฟื้นฟูภาพแล้ว ${it.path}: ${it.summary}") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                else -> done(false, error = "unknown action '${call.action}' (have: info/resize/crop/rotate/grayscale/scopes/adjust/upscale/restore)")
            }
        }

    private fun defaultDst(src: String, tag: String): String {
        val dot = src.lastIndexOf('.')
        return if (dot < 0) "$src-$tag" else "${src.substring(0, dot)}-$tag${src.substring(dot)}"
    }

    private fun done(ok: Boolean, output: String = "", error: String = ""): Outcome<ToolResult> =
        Outcome.Success(ToolResult(ok = ok, output = output, error = error))
}
