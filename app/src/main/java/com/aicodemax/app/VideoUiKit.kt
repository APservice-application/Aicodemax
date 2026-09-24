package com.aicodemax.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ceil

/** Editor-specific dark tokens sampled from the owner's VideoEditor PDF (not the global chat theme). */
internal object VideoInk {
    val background = Color(0xFF0B0C0D)
    val surface = Color(0xFF1C1D1F)
    val raised = Color(0xFF27292B)
    val border = Color(0xFF323436)
    val green = Color(0xFF0E9877)
    val greenDark = Color(0xFF096D56)
    val greenSoft = Color(0xFF173E33)
    val yellow = Color(0xFFF1C35D)
    val purple = Color(0xFF715594)
    val text = Color(0xFFF5F5F5)
    val muted = Color(0xFFA1A4A5)
    val danger = Color(0xFFF17C7B)
}

internal fun videoTime(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0) / 1000)
    return if (totalSeconds >= 3600) "%d:%02d:%02d".format(
        totalSeconds / 3600, (totalSeconds / 60) % 60, totalSeconds % 60,
    ) else "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

@Composable
internal fun VideoIconButton(
    glyph: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = VideoInk.text,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = if (enabled) tint else VideoInk.muted, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
internal fun VideoButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    prominent: Boolean = false,
    enabled: Boolean = true,
    glyph: String? = null,
) {
    val shape = RoundedCornerShape(13.dp)
    Row(
        modifier = modifier.defaultMinSize(minHeight = 48.dp).clip(shape)
            .background(if (prominent && enabled) VideoInk.green else VideoInk.raised)
            .clickable(enabled = enabled, onClickLabel = label, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (glyph != null) Text(glyph, color = VideoInk.text)
        Text(
            label,
            color = if (enabled) VideoInk.text else VideoInk.muted,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun VideoChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier.defaultMinSize(minHeight = 42.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) VideoInk.greenSoft else VideoInk.surface)
            .border(1.dp, if (selected) VideoInk.green else VideoInk.border, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClickLabel = label, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) VideoInk.text else VideoInk.muted, style = MaterialTheme.typography.labelMedium)
    }
}

/** Small, bounded real frame cache. Never invent thumbnails for missing files. */
private val frameCache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}

private fun readVideoFrame(context: Context, source: String, atMs: Long): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        if (source.startsWith("content:")) retriever.setDataSource(context, Uri.parse(source))
        else retriever.setDataSource(source)
        val original = if (android.os.Build.VERSION.SDK_INT >= 27) {
            retriever.getScaledFrameAtTime(atMs.coerceAtLeast(0) * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 256, 160)
        } else retriever.getFrameAtTime(atMs.coerceAtLeast(0) * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        if (original != null && (original.width > 320 || original.height > 240)) {
            val ratio = minOf(320f / original.width, 240f / original.height)
            Bitmap.createScaledBitmap(original, (original.width * ratio).toInt().coerceAtLeast(1), (original.height * ratio).toInt().coerceAtLeast(1), true)
        } else original
    } catch (_: Exception) {
        null
    } finally {
        retriever.release()
    }
}

private fun readImageFrame(context: Context, source: String): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    if (source.startsWith("content:")) context.contentResolver.openInputStream(Uri.parse(source))?.use {
        BitmapFactory.decodeStream(it, null, bounds)
    } else BitmapFactory.decodeFile(source, bounds)
    val options = BitmapFactory.Options().apply {
        inSampleSize = ceil(maxOf(bounds.outWidth / 320f, bounds.outHeight / 240f, 1f)).toInt()
    }
    if (source.startsWith("content:")) context.contentResolver.openInputStream(Uri.parse(source))?.use {
        BitmapFactory.decodeStream(it, null, options)
    } else BitmapFactory.decodeFile(source, options)
} catch (_: Exception) {
    null
}

@Composable
internal fun VideoFrame(
    source: String?,
    kind: String,
    atMs: Long = 0,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    val context = LocalContext.current
    val bucket = if (kind == "IMAGE") 0 else (atMs.coerceAtLeast(0) / 500) * 500
    val image = produceState<Bitmap?>(null, source, kind, bucket) {
        if (source.isNullOrBlank() || (!source.startsWith("content:") && !File(source).isFile)) {
            value = null
            return@produceState
        }
        val cacheKey = "$source#$kind#$bucket"
        val found = synchronized(frameCache) { frameCache.get(cacheKey) }
        value = found ?: withContext(Dispatchers.IO) {
            if (kind == "IMAGE") readImageFrame(context, source) else if (kind == "VIDEO") readVideoFrame(context, source, bucket) else null
        }?.also { synchronized(frameCache) { frameCache.put(cacheKey, it) } }
    }
    Box(modifier.background(VideoInk.raised), contentAlignment = Alignment.Center) {
        val bitmap = image.value
        if (bitmap == null) {
            Text(
                when (kind) { "VIDEO" -> "▶"; "AUDIO" -> "♫"; else -> "▧" },
                color = VideoInk.muted,
                style = MaterialTheme.typography.titleLarge,
            )
        } else {
            Image(bitmap = bitmap.asImageBitmap(), contentDescription = description, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}
