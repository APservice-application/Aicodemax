package com.aicodemax.ai.core

import com.aicodemax.core.common.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleBasedPlannerTest {
    private val planner: Planner = RuleBasedPlanner()

    @Test
    fun createFilePlansTwoEditorSteps() = runBlocking {
        val intent = UserIntent(IntentType.CREATE_FILE, "t", mapOf("path" to "a.txt", "content" to "hi"))
        val plan = (planner.plan(intent) as Outcome.Success<Plan>).value
        assertEquals(2, plan.steps.size)
        assertEquals("editor", plan.steps[0].toolId)
        assertEquals("set", plan.steps[0].action)
        assertEquals("a.txt", plan.steps[0].args["path"])
        assertEquals("save", plan.steps[1].action)
    }

    @Test
    fun missingPathFailsHonestly() = runBlocking {
        val intent = UserIntent(IntentType.READ_FILE, "t")
        val result = planner.plan(intent)
        assertTrue(result is Outcome.Failure)
        assertEquals("PLAN_NO_PATH", (result as Outcome.Failure).error.code)
    }

    @Test
    fun deleteRequiresPermission() = runBlocking {
        val intent = UserIntent(IntentType.DELETE_PATH, "t", mapOf("path" to "a.txt"))
        val plan = (planner.plan(intent) as Outcome.Success<Plan>).value
        assertEquals(1, plan.steps.size)
        assertTrue(plan.steps[0].needsPermission)
        assertEquals("delete", plan.steps[0].action)
    }

    @Test
    fun openUrlAndGitPlanRealSteps() = runBlocking {
        val open = UserIntent(IntentType.OPEN_URL, "t", mapOf("url" to "https://example.com"))
        val openPlan = (planner.plan(open) as Outcome.Success<Plan>).value
        assertEquals(1, openPlan.steps.size)
        assertEquals("browser", openPlan.steps[0].toolId)
        assertEquals("open", openPlan.steps[0].action)

        val status = UserIntent(IntentType.GIT_ACTION, "t", mapOf("action" to "status", "repo" to ""))
        val statusPlan = (planner.plan(status) as Outcome.Success<Plan>).value
        assertEquals("git", statusPlan.steps[0].toolId)
        assertEquals("status", statusPlan.steps[0].action)

        val commit = UserIntent(
            IntentType.GIT_ACTION, "t",
            mapOf("action" to "commit", "repo" to "", "message" to "done"),
        )
        val commitPlan = (planner.plan(commit) as Outcome.Success<Plan>).value
        assertEquals(listOf("stage", "commit"), commitPlan.steps.map { it.action })

        val push = planner.plan(UserIntent(IntentType.GIT_ACTION, "t", mapOf("action" to "push")))
        assertTrue(push is Outcome.Failure)
        assertEquals("PLAN_UNSUPPORTED", (push as Outcome.Failure).error.code)
    }

    @Test
    fun unavailableRuntimesReportHonestly() = runBlocking {
        // Terminal CLI adapter exists but is unrunnable today → honest BLOCKED.
        val run = planner.plan(UserIntent(IntentType.RUN_COMMAND, "t", mapOf("command" to "ls")))
        assertTrue(run is Outcome.Failure)
        assertEquals("CAPABILITY_BLOCKED", (run as Outcome.Failure).error.code)

        // No build/test engines registered yet → honest UNKNOWN.
        for (type in listOf(IntentType.BUILD_PROJECT, IntentType.RUN_TESTS)) {
            val result = planner.plan(UserIntent(type, "t"))
            assertTrue("$type", result is Outcome.Failure)
            assertEquals("CAPABILITY_UNKNOWN", (result as Outcome.Failure).error.code)
        }
        val chat = planner.plan(UserIntent(IntentType.CHAT, "hi"))
        assertEquals("PLAN_NOT_ACTIONABLE", (chat as Outcome.Failure).error.code)
    }

    @Test
    fun cp60VoicePlansRealSteps() = runBlocking {
        val speak = UserIntent(IntentType.VOICE_SPEAK, "t", mapOf("text" to "สวัสดี"))
        val speakPlan = (planner.plan(speak) as Outcome.Success<Plan>).value
        assertEquals(1, speakPlan.steps.size)
        assertEquals("voice", speakPlan.steps[0].toolId)
        assertEquals("speak", speakPlan.steps[0].action)
        assertEquals("สวัสดี", speakPlan.steps[0].args["text"])

        val listen = UserIntent(IntentType.VOICE_LISTEN, "t")
        val listenPlan = (planner.plan(listen) as Outcome.Success<Plan>).value
        assertEquals("voice", listenPlan.steps[0].toolId)
        assertEquals("listen", listenPlan.steps[0].action)

        val empty = planner.plan(UserIntent(IntentType.VOICE_SPEAK, "t", mapOf("text" to " ")))
        assertEquals("PLAN_NO_VOICE", (empty as Outcome.Failure).error.code)
    }

    @Test
    fun cp61ImagePlansRealSteps() = runBlocking {
        val info = UserIntent(IntentType.IMAGE_INFO, "t", mapOf("path" to "a.png"))
        val infoPlan = (planner.plan(info) as Outcome.Success<Plan>).value
        assertEquals("image", infoPlan.steps[0].toolId)
        assertEquals("info", infoPlan.steps[0].action)

        val resize = UserIntent(IntentType.IMAGE_RESIZE, "t", mapOf("path" to "a.png", "maxDim" to "800"))
        val resizePlan = (planner.plan(resize) as Outcome.Success<Plan>).value
        assertEquals("resize", resizePlan.steps[0].action)
        assertEquals("800", resizePlan.steps[0].args["maxDim"])

        val crop = UserIntent(
            IntentType.IMAGE_CROP, "t",
            mapOf("path" to "a.png", "x" to "1", "y" to "2", "w" to "3", "h" to "4"),
        )
        val cropPlan = (planner.plan(crop) as Outcome.Success<Plan>).value
        assertEquals("crop", cropPlan.steps[0].action)
        assertEquals("3", cropPlan.steps[0].args["w"])

        val gray = UserIntent(IntentType.IMAGE_GRAY, "t", mapOf("path" to "a.png"))
        val grayPlan = (planner.plan(gray) as Outcome.Success<Plan>).value
        assertEquals("grayscale", grayPlan.steps[0].action)

        val noPath = planner.plan(UserIntent(IntentType.IMAGE_INFO, "t"))
        assertEquals("PLAN_NO_IMAGE", (noPath as Outcome.Failure).error.code)
        val noRect = planner.plan(UserIntent(IntentType.IMAGE_CROP, "t", mapOf("path" to "a.png")))
        assertEquals("PLAN_NO_CROP", (noRect as Outcome.Failure).error.code)
    }

    @Test
    fun cp62AudioPlansRealSteps() = runBlocking {
        val info = UserIntent(IntentType.AUDIO_INFO, "t", mapOf("path" to "a.wav"))
        val infoPlan = (planner.plan(info) as Outcome.Success<Plan>).value
        assertEquals("audio", infoPlan.steps[0].toolId)
        assertEquals("info", infoPlan.steps[0].action)

        val trim = UserIntent(
            IntentType.AUDIO_TRIM, "t",
            mapOf("path" to "a.wav", "startMs" to "0", "endMs" to "500"),
        )
        val trimPlan = (planner.plan(trim) as Outcome.Success<Plan>).value
        assertEquals("trim", trimPlan.steps[0].action)
        assertEquals("500", trimPlan.steps[0].args["endMs"])

        val gain = UserIntent(IntentType.AUDIO_GAIN, "t", mapOf("path" to "a.wav", "db" to "-6"))
        val gainPlan = (planner.plan(gain) as Outcome.Success<Plan>).value
        assertEquals("gain", gainPlan.steps[0].action)

        val noPath = planner.plan(UserIntent(IntentType.AUDIO_INFO, "t"))
        assertEquals("PLAN_NO_AUDIO", (noPath as Outcome.Failure).error.code)
        val noRange = planner.plan(UserIntent(IntentType.AUDIO_TRIM, "t", mapOf("path" to "a.wav")))
        assertEquals("PLAN_NO_RANGE", (noRange as Outcome.Failure).error.code)
    }

    @Test
    fun cp63VideoPlansRealSteps() = runBlocking {
        val info = UserIntent(IntentType.VIDEO_INFO, "t", mapOf("path" to "a.mp4"))
        val infoPlan = (planner.plan(info) as Outcome.Success<Plan>).value
        assertEquals("video", infoPlan.steps[0].toolId)
        assertEquals("info", infoPlan.steps[0].action)

        val trim = UserIntent(
            IntentType.VIDEO_TRIM, "t",
            mapOf("path" to "a.mp4", "startMs" to "0", "endMs" to "10000"),
        )
        val trimPlan = (planner.plan(trim) as Outcome.Success<Plan>).value
        assertEquals("trim", trimPlan.steps[0].action)

        val thumb = UserIntent(IntentType.VIDEO_THUMB, "t", mapOf("path" to "a.mp4", "timeMs" to "2000"))
        val thumbPlan = (planner.plan(thumb) as Outcome.Success<Plan>).value
        assertEquals("thumbnail", thumbPlan.steps[0].action)
        assertEquals("2000", thumbPlan.steps[0].args["timeMs"])

        val noPath = planner.plan(UserIntent(IntentType.VIDEO_AUDIO, "t"))
        assertEquals("PLAN_NO_VIDEO", (noPath as Outcome.Failure).error.code)
    }

    @Test
    fun cp64ProjectPlansRealSteps() = runBlocking {
        val create = UserIntent(IntentType.PROJECT_NEW, "t", mapOf("name" to "demo"))
        val createPlan = (planner.plan(create) as Outcome.Success<Plan>).value
        assertEquals("media", createPlan.steps[0].toolId)
        assertEquals("project.create", createPlan.steps[0].action)

        val list = UserIntent(IntentType.PROJECT_LIST, "t")
        val listPlan = (planner.plan(list) as Outcome.Success<Plan>).value
        assertEquals("project.list", listPlan.steps[0].action)

        val import = UserIntent(IntentType.ASSET_IMPORT, "t", mapOf("path" to "a.mp4"))
        val importPlan = (planner.plan(import) as Outcome.Success<Plan>).value
        assertEquals("asset.import", importPlan.steps[0].action)

        val restore = UserIntent(IntentType.PROJECT_RESTORE, "t", mapOf("version" to "2"))
        val restorePlan = (planner.plan(restore) as Outcome.Success<Plan>).value
        assertEquals("version.restore", restorePlan.steps[0].action)

        val noFile = planner.plan(UserIntent(IntentType.ASSET_IMPORT, "t"))
        assertEquals("PLAN_NO_MEDIA_FILE", (noFile as Outcome.Failure).error.code)
        val noVersion = planner.plan(UserIntent(IntentType.PROJECT_RESTORE, "t"))
        assertEquals("PLAN_NO_VERSION", (noVersion as Outcome.Failure).error.code)
    }

    @Test
    fun cp72ClipPlansRealSteps() = runBlocking {
        val split = (planner.plan(UserIntent(IntentType.CLIP_SPLIT, "t", mapOf("clipIndex" to "1", "atMs" to "2000"))) as Outcome.Success<Plan>).value
        assertEquals("timeline.splitClip", split.steps[0].action)
        val noSplit = planner.plan(UserIntent(IntentType.CLIP_SPLIT, "t"))
        assertEquals("PLAN_NO_SPLIT", (noSplit as Outcome.Failure).error.code)
        val del = (planner.plan(UserIntent(IntentType.CLIP_DELETE, "t", mapOf("clipIndex" to "2"))) as Outcome.Success<Plan>).value
        assertEquals("timeline.deleteClip", del.steps[0].action)
        val flags = (planner.plan(UserIntent(IntentType.TRACK_FLAGS, "t", mapOf("trackId" to "V1", "muted" to "true"))) as Outcome.Success<Plan>).value
        assertEquals("timeline.trackFlags", flags.steps[0].action)
        assertEquals("true", flags.steps[0].args["muted"])
    }

    @Test
    fun cp71UndoMgmtPlansRealSteps() = runBlocking {
        val undo = (planner.plan(UserIntent(IntentType.EDIT_UNDO, "t")) as Outcome.Success<Plan>).value
        assertEquals("media", undo.steps[0].toolId)
        assertEquals("edit.undo", undo.steps[0].action)
        val redo = (planner.plan(UserIntent(IntentType.EDIT_REDO, "t")) as Outcome.Success<Plan>).value
        assertEquals("edit.redo", redo.steps[0].action)
        val rename = (planner.plan(UserIntent(IntentType.PROJECT_RENAME, "t", mapOf("name" to "x"))) as Outcome.Success<Plan>).value
        assertEquals("project.rename", rename.steps[0].action)
        val noName = planner.plan(UserIntent(IntentType.PROJECT_RENAME, "t"))
        assertEquals("PLAN_NO_NAME", (noName as Outcome.Failure).error.code)
        val checkpoint = (planner.plan(UserIntent(IntentType.PROJECT_CHECKPOINT, "t")) as Outcome.Success<Plan>).value
        assertEquals("checkpoint.save", checkpoint.steps[0].action)
    }

    @Test
    fun cp67RenderPlansRealSteps() = runBlocking {
        val start = (planner.plan(UserIntent(IntentType.RENDER_START, "t", mapOf("preset" to "480p"))) as Outcome.Success<Plan>).value
        assertEquals("render", start.steps[0].toolId)
        assertEquals("runNow", start.steps[0].action)
        assertEquals("480p", start.steps[0].args["preset"])
        val status = (planner.plan(UserIntent(IntentType.RENDER_STATUS, "t")) as Outcome.Success<Plan>).value
        assertEquals("list", status.steps[0].action)
        val export = (planner.plan(UserIntent(IntentType.RENDER_EXPORT, "t")) as Outcome.Success<Plan>).value
        assertEquals("export", export.steps[0].action)
        val share = (planner.plan(UserIntent(IntentType.SHARE_MEDIA, "t")) as Outcome.Success<Plan>).value
        assertEquals("render", share.steps[0].toolId)
        assertEquals("export", share.steps[0].action)
    }

    @Test
    fun cp66SubtitlePlansRealSteps() = runBlocking {
        val make = UserIntent(
            IntentType.SUBTITLE_MAKE, "t",
            mapOf("transcript" to "hi", "path" to "a.wav"),
        )
        val makePlan = (planner.plan(make) as Outcome.Success<Plan>).value
        assertEquals("subtitle", makePlan.steps[0].toolId)
        assertEquals("make", makePlan.steps[0].action)
        assertEquals("a.wav", makePlan.steps[0].args["mediaPath"])

        val shift = UserIntent(IntentType.SUBTITLE_SHIFT, "t", mapOf("path" to "a.srt", "offsetMs" to "500"))
        val shiftPlan = (planner.plan(shift) as Outcome.Success<Plan>).value
        assertEquals("shift", shiftPlan.steps[0].action)

        val burn = UserIntent(IntentType.SUBTITLE_BURN, "t", mapOf("src" to "a.mp4", "srt" to "a.srt"))
        val burnPlan = (planner.plan(burn) as Outcome.Success<Plan>).value
        assertEquals("burn", burnPlan.steps[0].action)

        val noText = planner.plan(UserIntent(IntentType.SUBTITLE_MAKE, "t", mapOf("path" to "a.wav")))
        assertEquals("PLAN_NO_TRANSCRIPT", (noText as Outcome.Failure).error.code)
        val noBurn = planner.plan(UserIntent(IntentType.SUBTITLE_BURN, "t", mapOf("src" to "a.mp4")))
        assertEquals("PLAN_NO_BURN", (noBurn as Outcome.Failure).error.code)
    }
}
