package com.aicodemax.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

/**
 * CP-34: tiny markdown renderer — **bold**, `code`, and [text](url) links
 * (shown as text; link following comes with the browser handoff).
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    val styled = remember(text) { renderMarkdown(text) }
    Text(
        text = styled,
        modifier = modifier,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.bodyMedium,
    )
}

private fun renderMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    var index = 0
    while (index < text.length) {
        val bold = text.indexOf("**", index)
        val code = text.indexOf("`", index)
        val link = text.indexOf("[", index)
        val next = listOf(bold, code, link).filter { it >= 0 }.minOrNull()
        if (next == null) {
            append(text.substring(index))
            break
        }
        append(text.substring(index, next))
        when (next) {
            bold -> {
                val end = text.indexOf("**", bold + 2)
                if (end < 0) {
                    append("**")
                    index = bold + 2
                } else {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(text.substring(bold + 2, end))
                    pop()
                    index = end + 2
                }
            }
            code -> {
                val end = text.indexOf("`", code + 1)
                if (end < 0) {
                    append("`")
                    index = code + 1
                } else {
                    pushStyle(SpanStyle(fontFamily = FontFamily.Monospace))
                    append(text.substring(code + 1, end))
                    pop()
                    index = end + 1
                }
            }
            else -> {
                // [text](url) — render text only.
                val mid = text.indexOf("](", link + 1)
                val end = if (mid >= 0) text.indexOf(")", mid + 2) else -1
                if (mid < 0 || end < 0) {
                    append("[")
                    index = link + 1
                } else {
                    pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold))
                    append(text.substring(link + 1, mid))
                    pop()
                    index = end + 1
                }
            }
        }
    }
}
