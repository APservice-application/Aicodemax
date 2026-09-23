package com.aicodemax.data.media

/**
 * CP-106 expert UX: hardware-keyboard shortcuts for the timeline (§expert).
 * Pure codes (Android KeyEvent values, no android dependency) so AI, tests,
 * and Compose ([keyCode][androidx.compose.ui.input.key.Key.keyCode]) share
 * one mapping.
 */
enum class TimelineShortcut {
    UNDO,
    REDO,
    NEXT_CLIP,
    PREV_CLIP,
    DELETE_CLIP,
}

object TimelineShortcuts {
    const val KEY_Z = 54
    const val KEY_Y = 55
    const val KEY_RIGHT = 22
    const val KEY_LEFT = 21
    const val KEY_TAB = 61
    const val KEY_DEL = 67

    fun resolve(keyCode: Int, ctrl: Boolean, shift: Boolean = false): TimelineShortcut? = when {
        ctrl && keyCode == KEY_Z && !shift -> TimelineShortcut.UNDO
        ctrl && (keyCode == KEY_Y || (keyCode == KEY_Z && shift)) -> TimelineShortcut.REDO
        !ctrl && (keyCode == KEY_RIGHT || keyCode == KEY_TAB) -> TimelineShortcut.NEXT_CLIP
        !ctrl && keyCode == KEY_LEFT -> TimelineShortcut.PREV_CLIP
        !ctrl && keyCode == KEY_DEL -> TimelineShortcut.DELETE_CLIP
        else -> null
    }

    val help: String =
        "คีย์ลัดไทม์ไลน์: Ctrl+Z เลิกทำ · Ctrl+Y ทำซ้ำ · ←/→ เลือกคลิป · Del ลบคลิปที่เลือก"
}
