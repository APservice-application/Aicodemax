package com.aicodemax.ai.agents

import com.aicodemax.ai.core.IntentType
import com.aicodemax.ai.core.UserIntent
import com.aicodemax.tools.capability.CapabilityBinding

/**
 * CP-148: the on-device context is 2048 tokens — the planner may NOT see all
 * 196 capabilities. This picks the ~28 relevant ones per intent (recall over
 * precision: unknown intents get the most common tools).
 */
object CatalogFilter {
    const val CAP = 28

    private val MEDIA = setOf("media", "audio", "video", "image", "render", "subtitle")
    private val COMMON = setOf("files", "editor", "browser", "terminal", "memory", "skill", "debug", "git")

    fun forIntent(intent: UserIntent, all: List<CapabilityBinding>, cap: Int = CAP): List<CapabilityBinding> {
        val prefixes: Set<String> = when (intent.type) {
            IntentType.CREATE_FILE, IntentType.READ_FILE, IntentType.LIST_FILES,
            IntentType.MAKE_DIR, IntentType.DELETE_PATH, IntentType.SEARCH_FILES,
            -> setOf("files", "editor")

            IntentType.SEARCH_ALL -> setOf("files", "media", "skill")
            IntentType.RUN_COMMAND -> setOf("terminal")
            IntentType.OPEN_URL -> setOf("browser")
            IntentType.BUILD_PROJECT -> setOf("build")
            IntentType.RUN_TESTS -> setOf("test")
            IntentType.GIT_ACTION -> setOf("git")
            IntentType.BROWSER_OPEN, IntentType.BROWSER_CLOSE, IntentType.BROWSER_LIST,
            IntentType.BROWSER_READ, IntentType.BROWSER_CLICK, IntentType.BROWSER_TYPE,
            -> setOf("browser")

            IntentType.DEBUG_CODE, IntentType.DEBUG_BENCH -> setOf("debug")
            IntentType.MEMORY_SAVE, IntentType.MEMORY_RECALL, IntentType.MEMORY_LESSONS -> setOf("memory")
            IntentType.VOICE_SPEAK, IntentType.VOICE_LISTEN -> setOf("voice", "audio")
            IntentType.IMAGE_INFO, IntentType.IMAGE_RESIZE, IntentType.IMAGE_CROP,
            IntentType.IMAGE_ROTATE, IntentType.IMAGE_GRAY, IntentType.IMAGE_ADJUST,
            IntentType.IMAGE_UPSCALE, IntentType.IMAGE_RESTORE, IntentType.IMAGE_SCOPES,
            -> setOf("image")

            IntentType.AUDIO_INFO, IntentType.AUDIO_TRIM, IntentType.AUDIO_CONCAT,
            IntentType.AUDIO_GAIN, IntentType.AUDIO_FADE, IntentType.VOICE_FX,
            IntentType.SYNTH_MUSIC, IntentType.SYNTH_SFX, IntentType.RECORD_START,
            IntentType.RECORD_STOP, IntentType.PODCAST, IntentType.AUDIO_MIX,
            IntentType.AUDIO_NORMALIZE, IntentType.BEATS,
            -> setOf("audio")

            IntentType.VIDEO_INFO, IntentType.VIDEO_TRIM, IntentType.VIDEO_THUMB,
            IntentType.VIDEO_AUDIO, IntentType.VIDEO_PROXY, IntentType.VIDEO_SCOPES,
            -> setOf("video")

            // Non-plannable / chat-handled: the LLM gets nothing (honest Failure downstream).
            IntentType.CHAT, IntentType.UNKNOWN, IntentType.STOP_TASK,
            IntentType.SYSTEM_STATUS, IntentType.OPEN_SETTINGS, IntentType.LLM_CONNECT,
            -> return emptyList()

            // Everything else is media/timeline/render work.
            else -> MEDIA
        }
        val picked = all.filter { it.capabilityId.substringBefore(".") in prefixes }
        val merged = (picked + all.filter { it.capabilityId.substringBefore(".") in COMMON && it !in picked })
        return merged.take(cap.coerceAtLeast(1))
    }

    /** Replan context: same tool family first, then the common tools. */
    fun forTool(toolId: String, all: List<CapabilityBinding>, cap: Int = CAP): List<CapabilityBinding> {
        val family = toolId.substringBefore(".")
        val picked = all.filter { it.capabilityId.substringBefore(".") == family }
        val merged = (picked + all.filter { it !in picked })
        return merged.take(cap.coerceAtLeast(1))
    }
}
