package com.aicodemax.tools.image

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome

/** Result of an image operation. */
data class ImageInfo(
    val path: String,
    val format: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long = -1,
) {
    val summary: String get() = "$format ${width}x$height"
}

/**
 * CP-61 image contract: probe + edit (resize/crop/rotate/grayscale).
 * Paths are absolute; Android impl converts via Bitmap, pixel math stays in [ImageOps].
 */
interface ImagePort {
    suspend fun info(path: String): Outcome<ImageInfo>
    suspend fun resize(src: String, dst: String, maxDim: Int): Outcome<ImageInfo>
    suspend fun crop(src: String, dst: String, x: Int, y: Int, w: Int, h: Int): Outcome<ImageInfo>
    suspend fun rotate(src: String, dst: String, degrees: Int): Outcome<ImageInfo>
    suspend fun grayscale(src: String, dst: String): Outcome<ImageInfo>
}

/**
 * In-memory fake: stores [PixelImage] by path. Real files on disk are probed
 * for headers ([info] only); edits require the image to be [put] first.
 */
class InMemoryImagePort : ImagePort {
    private val store = mutableMapOf<String, PixelImage>()

    fun put(path: String, image: PixelImage) {
        store[path] = image
    }

    fun get(path: String): PixelImage? = store[path]

    override suspend fun info(path: String): Outcome<ImageInfo> {
        store[path]?.let { return Outcome.Success(ImageInfo(path, "MEM", it.width, it.height)) }
        return when (val probed = ImageProbe.probe(java.io.File(path))) {
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

    private inline fun edit(src: String, dst: String, code: String, op: (PixelImage) -> PixelImage): Outcome<ImageInfo> {
        val image = store[src]
            ?: return Outcome.Failure(AppError(code, "ไม่พบรูป $src (fake นี้ต้อง put() ก่อน)"))
        return try {
            val out = op(image)
            store[dst] = out
            Outcome.Success(ImageInfo(dst, "MEM", out.width, out.height))
        } catch (e: IllegalArgumentException) {
            Outcome.Failure(AppError(code, e.message ?: "bad args"))
        }
    }
}
