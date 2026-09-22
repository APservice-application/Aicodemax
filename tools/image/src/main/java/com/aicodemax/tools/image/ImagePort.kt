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
    // CP-78 histogram scopes (§101).
    suspend fun scopes(path: String): Outcome<FrameScopes>
    // CP-89 photo edit/upscale/restore (§37).
    suspend fun adjust(src: String, dst: String, brightness: Int, contrast: Int, saturation: Int, sharpness: Int): Outcome<ImageInfo>
    suspend fun upscale(src: String, dst: String, scale: Int): Outcome<ImageInfo>
    suspend fun restore(src: String, dst: String, denoise: Boolean, deFade: Boolean, whiteBalance: Boolean): Outcome<ImageInfo>
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

    override suspend fun adjust(src: String, dst: String, brightness: Int, contrast: Int, saturation: Int, sharpness: Int): Outcome<ImageInfo> =
        edit(src, dst, "IMAGE_ADJUST") { PhotoOps.adjust(it, brightness, contrast, saturation, sharpness) }

    override suspend fun upscale(src: String, dst: String, scale: Int): Outcome<ImageInfo> =
        edit(src, dst, "IMAGE_UPSCALE") { PhotoOps.upscale(it, scale) }

    override suspend fun restore(src: String, dst: String, denoise: Boolean, deFade: Boolean, whiteBalance: Boolean): Outcome<ImageInfo> =
        edit(src, dst, "IMAGE_RESTORE") { PhotoOps.restore(it, denoise, deFade, whiteBalance) }

    override suspend fun scopes(path: String): Outcome<FrameScopes> {
        val image = store[path]
            ?: return Outcome.Failure(AppError("IMAGE_SCOPES", "ไม่พบรูป $path (fake นี้ต้อง put() ก่อน)"))
        return Outcome.Success(ColorScopes.analyze(image))
    }

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
