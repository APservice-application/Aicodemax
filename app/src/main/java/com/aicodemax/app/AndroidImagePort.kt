package com.aicodemax.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import com.aicodemax.tools.image.ImageInfo
import com.aicodemax.tools.image.ImageOps
import com.aicodemax.tools.image.ImagePort
import com.aicodemax.tools.image.ImageProbe
import com.aicodemax.tools.image.PixelImage
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * CP-61 Android images: Bitmap <-> [PixelImage] adapter.
 * All pixel math runs in [ImageOps] (unit-tested on JVM); this class only
 * decodes/encodes files. Formats: PNG/JPEG/WebP (encode), +GIF/BMP (probe/decode).
 */
class AndroidImagePort : ImagePort {
    override suspend fun info(path: String): Outcome<ImageInfo> =
        withContext(Dispatchers.IO) {
            when (val probed = ImageProbe.probe(File(path))) {
                is Outcome.Failure -> probed
                is Outcome.Success -> Outcome.Success(
                    ImageInfo(path, probed.value.format, probed.value.width, probed.value.height, probed.value.sizeBytes),
                )
            }
        }

    override suspend fun resize(src: String, dst: String, maxDim: Int): Outcome<ImageInfo> =
        edit(src, dst, "IMAGE_RESIZE") { ImageOps.resize(it, maxDim) }

    override suspend fun crop(src: String, dst: String, x: Int, y: Int, w: Int, h: Int): Outcome<ImageInfo> =
        edit(src, dst, "IMAGE_CROP") { ImageOps.crop(it, x, y, w, h) }

    override suspend fun rotate(src: String, dst: String, degrees: Int): Outcome<ImageInfo> =
        edit(src, dst, "IMAGE_ROTATE") { ImageOps.rotate(it, degrees) }

    override suspend fun grayscale(src: String, dst: String): Outcome<ImageInfo> =
        edit(src, dst, "IMAGE_GRAY") { ImageOps.grayscale(it) }

    private suspend fun edit(
        src: String,
        dst: String,
        code: String,
        op: (PixelImage) -> PixelImage,
    ): Outcome<ImageInfo> = withContext(Dispatchers.IO) {
        val bitmap = try {
            BitmapFactory.decodeFile(src)
        } catch (e: Exception) {
            return@withContext Outcome.Failure(AppError(code, "เปิดรูปไม่ได้: ${e.message}"))
        } ?: return@withContext Outcome.Failure(AppError(code, "เปิดรูปไม่ได้ (ไฟล์เสียหรือไม่ใช่รูป): $src"))
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        bitmap.recycle()
        val out = try {
            op(PixelImage(w, h, pixels))
        } catch (e: IllegalArgumentException) {
            return@withContext Outcome.Failure(AppError(code, e.message ?: "bad args"))
        }
        val format = formatFor(dst)
            ?: return@withContext Outcome.Failure(AppError(code, "นามสกุลไฟล์ปลายทางต้องเป็น .png/.jpg/.webp: $dst"))
        try {
            val result = Bitmap.createBitmap(out.width, out.height, Bitmap.Config.ARGB_8888)
            result.setPixels(out.pixels, 0, out.width, 0, 0, out.width, out.height)
            FileOutputStream(dst).use { fo ->
                if (!result.compress(format, 92, fo)) {
                    result.recycle()
                    return@withContext Outcome.Failure(AppError(code, "เขียนไฟล์ปลายทางไม่ได้: $dst"))
                }
            }
            result.recycle()
        } catch (e: Exception) {
            return@withContext Outcome.Failure(AppError(code, "เขียนไฟล์ไม่ได้: ${e.message}"))
        }
        val size = try {
            File(dst).length()
        } catch (_: Exception) {
            -1L
        }
        Outcome.Success(ImageInfo(dst, format.name, out.width, out.height, size))
    }

    private fun formatFor(dst: String): Bitmap.CompressFormat? = when (dst.substringAfterLast('.', "").lowercase()) {
        "png" -> Bitmap.CompressFormat.PNG
        "jpg", "jpeg" -> Bitmap.CompressFormat.JPEG
        "webp" -> Bitmap.CompressFormat.WEBP_LOSSY
        else -> null
    }
}
