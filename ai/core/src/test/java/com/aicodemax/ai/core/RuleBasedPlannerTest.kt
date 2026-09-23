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
    fun cp75SpeedPlansRealSteps() = runBlocking {
        val speed = (planner.plan(UserIntent(IntentType.CLIP_SPEED, "t", mapOf("clipIndex" to "1", "rate" to "50"))) as Outcome.Success<Plan>).value
        assertEquals("timeline.setSpeed", speed.steps[0].action)
        assertEquals("50", speed.steps[0].args["rate"])
        val rev = (planner.plan(UserIntent(IntentType.CLIP_REVERSE, "t", mapOf("clipIndex" to "1"))) as Outcome.Success<Plan>).value
        assertEquals("true", rev.steps[0].args["reverse"])
        val missing = planner.plan(UserIntent(IntentType.CLIP_SPEED, "t"))
        assertEquals("PLAN_NO_CLIP", (missing as Outcome.Failure).error.code)
    }

    @Test
    fun cp102ProxyCachePlans() = runBlocking {
        val px = (planner.plan(UserIntent(IntentType.VIDEO_PROXY, "t", mapOf("path" to "v.mp4"))) as Outcome.Success<Plan>).value
        assertEquals("proxy", px.steps[0].action)
        assertEquals("video", px.steps[0].toolId)
        val st = (planner.plan(UserIntent(IntentType.CACHE_STATUS, "t", emptyMap())) as Outcome.Success<Plan>).value
        assertEquals("cache.status", st.steps[0].action)
        val cl = (planner.plan(UserIntent(IntentType.CACHE_CLEAR, "t", emptyMap())) as Outcome.Success<Plan>).value
        assertEquals("cache.clear", cl.steps[0].action)
    }

    @Test
    fun cp100BrandPackageBatchPlans() = runBlocking {
        val save = (planner.plan(UserIntent(IntentType.BRAND_SAVE, "t", mapOf("name" to "x"))) as Outcome.Success<Plan>).value
        assertEquals("brand.save", save.steps[0].action)
        val pack = (planner.plan(UserIntent(IntentType.PROJECT_PACKAGE, "t", emptyMap())) as Outcome.Success<Plan>).value
        assertEquals("package", pack.steps[0].action)
        val batch = (planner.plan(UserIntent(IntentType.RENDER_BATCH, "t", mapOf("all" to "true"))) as Outcome.Success<Plan>).value
        assertEquals("batch", batch.steps[0].action)
        assertEquals("render", batch.steps[0].toolId)
    }

    @Test
    fun cp99DefersHonestly() = runBlocking {
        val lip = planner.plan(UserIntent(IntentType.LIPSYNC, "t", emptyMap()))
        assertTrue(lip is Outcome.Failure)
        assertEquals("DEFERRED_LIPSYNC", (lip as Outcome.Failure).error.code)
        val pre = planner.plan(UserIntent(IntentType.PRESENTER, "t", emptyMap()))
        assertTrue(pre is Outcome.Failure)
        assertEquals("DEFERRED_PRESENTER", (pre as Outcome.Failure).error.code)
    }

    @Test
    fun cp98SubtitleTranslatePlans() = runBlocking {
        val tr = (planner.plan(UserIntent(IntentType.SUBTITLE_TRANSLATE, "t", mapOf("path" to "a.srt"))) as Outcome.Success<Plan>).value
        assertEquals("translate", tr.steps[0].action)
        assertEquals("subtitle", tr.steps[0].toolId)
    }

    @Test
    fun cp97ScriptVideoPlans() = runBlocking {
        val sv = (planner.plan(UserIntent(IntentType.SCRIPT_VIDEO, "t", mapOf("script" to "hi"))) as Outcome.Success<Plan>).value
        assertEquals("script.video", sv.steps[0].action)
    }

    @Test
    fun cp96AiPlanPlans() = runBlocking {
        val p = (planner.plan(UserIntent(IntentType.AI_PLAN, "t", mapOf("mode" to "vlog", "topic" to "ทะเล"))) as Outcome.Success<Plan>).value
        assertEquals("text.aiplan", p.steps[0].action)
        assertEquals("vlog", p.steps[0].args["mode"])
    }

    @Test
    fun cp95PodcastPlans() = runBlocking {
        val pod = (planner.plan(UserIntent(IntentType.PODCAST, "t", mapOf("voice" to "v.wav"))) as Outcome.Success<Plan>).value
        assertEquals("podcast", pod.steps[0].action)
        val mix = (planner.plan(UserIntent(IntentType.AUDIO_MIX, "t", mapOf("srcA" to "a.wav", "srcB" to "b.wav"))) as Outcome.Success<Plan>).value
        assertEquals("mix", mix.steps[0].action)
        val norm = (planner.plan(UserIntent(IntentType.AUDIO_NORMALIZE, "t", mapOf("src" to "a.wav"))) as Outcome.Success<Plan>).value
        assertEquals("normalize", norm.steps[0].action)
    }

    @Test
    fun cp94RecordPlans() = runBlocking {
        val start = (planner.plan(UserIntent(IntentType.RECORD_START, "t", mapOf("dst" to "r.m4a"))) as Outcome.Success<Plan>).value
        assertEquals("recordStart", start.steps[0].action)
        val stop = (planner.plan(UserIntent(IntentType.RECORD_STOP, "t", emptyMap())) as Outcome.Success<Plan>).value
        assertEquals("recordStop", stop.steps[0].action)
        val screen = planner.plan(UserIntent(IntentType.SCREEN_RECORD, "t", emptyMap()))
        assertTrue(screen is Outcome.Failure)
        assertEquals("DEFERRED_SCREEN", (screen as Outcome.Failure).error.code)
    }

    @Test
    fun cp92CamTrackDefersHonestly() = runBlocking {
        val r = planner.plan(UserIntent(IntentType.CAM_TRACK, "t", mapOf("clipIndex" to "1")))
        assertTrue(r is Outcome.Failure)
        assertEquals("DEFERRED_CAMTRACK", (r as Outcome.Failure).error.code)
    }

    @Test
    fun cp93BeautyDefersHonestly() = runBlocking {
        val r = planner.plan(UserIntent(IntentType.BEAUTY, "t", mapOf("clipIndex" to "1")))
        assertTrue(r is Outcome.Failure)
        assertEquals("DEFERRED_BEAUTY", (r as Outcome.Failure).error.code)
    }

    @Test
    fun cp91EnhancePlansRealStep() = runBlocking {
        val en = (planner.plan(UserIntent(IntentType.CLIP_ENHANCE, "t", mapOf("clipIndex" to "1"))) as Outcome.Success<Plan>).value
        assertEquals("timeline.enhance", en.steps[0].action)
    }

    @Test
    fun cp90ColorProPlansRealSteps() = runBlocking {
        val match = (planner.plan(UserIntent(IntentType.COLOR_MATCH, "t", mapOf("clipIndex" to "2", "refIndex" to "1"))) as Outcome.Success<Plan>).value
        assertEquals("timeline.colorMatch", match.steps[0].action)
        val wb = (planner.plan(UserIntent(IntentType.COLOR_WB, "t", mapOf("clipIndex" to "1"))) as Outcome.Success<Plan>).value
        assertEquals("timeline.colorAuto", wb.steps[0].action)
        val warm = (planner.plan(UserIntent(IntentType.COLOR_WB, "t", mapOf("clipIndex" to "1", "preset" to "warm"))) as Outcome.Success<Plan>).value
        assertEquals("timeline.setColor", warm.steps[0].action)
    }

    @Test
    fun cp89PhotoPlansRealSteps() = runBlocking {
        val adj = (planner.plan(UserIntent(IntentType.IMAGE_ADJUST, "t", mapOf("path" to "a.png", "brightness" to "20"))) as Outcome.Success<Plan>).value
        assertEquals("adjust", adj.steps[0].action)
        val up = (planner.plan(UserIntent(IntentType.IMAGE_UPSCALE, "t", mapOf("path" to "a.png"))) as Outcome.Success<Plan>).value
        assertEquals("upscale", up.steps[0].action)
        val re = (planner.plan(UserIntent(IntentType.IMAGE_RESTORE, "t", mapOf("path" to "a.png"))) as Outcome.Success<Plan>).value
        assertEquals("restore", re.steps[0].action)
    }

    @Test
    fun cp88ReframeCanvasPlansRealSteps() = runBlocking {
        val re = (planner.plan(UserIntent(IntentType.REFRAME, "t", mapOf("clipIndex" to "1", "aspect" to "9:16"))) as Outcome.Success<Plan>).value
        assertEquals("timeline.reframe", re.steps[0].action)
        assertEquals("9:16", re.steps[0].args["aspect"])
        val cv = (planner.plan(UserIntent(IntentType.SET_CANVAS, "t", mapOf("aspect" to "1:1"))) as Outcome.Success<Plan>).value
        assertEquals("timeline.setCanvas", cv.steps[0].action)
    }

    @Test
    fun cp87CutHighlightPlansRealSteps() = runBlocking {
        val cut = (planner.plan(UserIntent(IntentType.AUTOCUT, "t", mapOf("clipIndex" to "1"))) as Outcome.Success<Plan>).value
        assertEquals("timeline.autocut", cut.steps[0].action)
        val hi = (planner.plan(UserIntent(IntentType.HIGHLIGHTS, "t", mapOf("clipIndex" to "1"))) as Outcome.Success<Plan>).value
        assertEquals("timeline.highlights", hi.steps[0].action)
    }

    @Test
    fun cp86VoiceSynthPlansRealSteps() = runBlocking {
        val fx = (planner.plan(UserIntent(IntentType.VOICE_FX, "t", mapOf("path" to "a.wav", "semitones" to "5"))) as Outcome.Success<Plan>).value
        assertEquals("voicefx", fx.steps[0].action)
        val music = (planner.plan(UserIntent(IntentType.SYNTH_MUSIC, "t", mapOf("style" to "calm"))) as Outcome.Success<Plan>).value
        assertEquals("synthmusic", music.steps[0].action)
        val sfx = (planner.plan(UserIntent(IntentType.SYNTH_SFX, "t", mapOf("kind" to "riser"))) as Outcome.Success<Plan>).value
        assertEquals("synthsfx", sfx.steps[0].action)
    }

    @Test
    fun cp85BeatVolumePlansRealSteps() = runBlocking {
        val clip = (planner.plan(UserIntent(IntentType.BEATS, "t", mapOf("clipIndex" to "1"))) as Outcome.Success<Plan>).value
        assertEquals("timeline.beatsToMarkers", clip.steps[0].action)
        val file = (planner.plan(UserIntent(IntentType.BEATS, "t", mapOf("path" to "s.wav"))) as Outcome.Success<Plan>).value
        assertEquals("beats", file.steps[0].action)
        assertEquals("audio", file.steps[0].toolId)
        val vol = (planner.plan(UserIntent(IntentType.CLIP_VOLUME, "t", mapOf("clipIndex" to "1", "volume" to "70"))) as Outcome.Success<Plan>).value
        assertEquals("timeline.volume", vol.steps[0].action)
        val noval = planner.plan(UserIntent(IntentType.CLIP_VOLUME, "t", mapOf("clipIndex" to "1")))
        assertEquals("PLAN_NO_VALUE", (noval as Outcome.Failure).error.code)
    }

    @Test
    fun cp84MotionSlidePlansRealSteps() = runBlocking {
        val motion = (planner.plan(UserIntent(IntentType.CLIP_MOTION, "t", mapOf("clipIndex" to "1", "dir" to "in"))) as Outcome.Success<Plan>).value
        assertEquals("timeline.motion", motion.steps[0].action)
        val slide = (planner.plan(UserIntent(IntentType.SLIDESHOW, "t", mapOf("assets" to "a.png"))) as Outcome.Success<Plan>).value
        assertEquals("timeline.slideshow", slide.steps[0].action)
        val noassets = planner.plan(UserIntent(IntentType.SLIDESHOW, "t"))
        assertEquals("PLAN_NO_ASSETS", (noassets as Outcome.Failure).error.code)
    }

    @Test
    fun cp83GenPlansRealSteps() = runBlocking {
        val make = (planner.plan(UserIntent(IntentType.GEN_MAKE, "t", mapOf("kind" to "poster", "prompt" to "Hi"))) as Outcome.Success<Plan>).value
        assertEquals("gen.make", make.steps[0].action)
        val list = (planner.plan(UserIntent(IntentType.GEN_LIST, "t")) as Outcome.Success<Plan>).value
        assertEquals("gen.list", list.steps[0].action)
        val noprompt = planner.plan(UserIntent(IntentType.GEN_MAKE, "t", mapOf("kind" to "tts")))
        assertEquals("PLAN_NO_PROMPT", (noprompt as Outcome.Failure).error.code)
        val nofile = planner.plan(UserIntent(IntentType.GEN_MAKE, "t", mapOf("kind" to "stylize")))
        assertEquals("PLAN_NO_FILE", (nofile as Outcome.Failure).error.code)
    }

    @Test
    fun cp82TemplateLibPlansRealSteps() = runBlocking {
        val save = (planner.plan(UserIntent(IntentType.TEMPLATE_SAVE, "t", mapOf("name" to "X"))) as Outcome.Success<Plan>).value
        assertEquals("template.save", save.steps[0].action)
        val apply = (planner.plan(UserIntent(IntentType.TEMPLATE_APPLY, "t", mapOf("name" to "X"))) as Outcome.Success<Plan>).value
        assertEquals("template.apply", apply.steps[0].action)
        val list = (planner.plan(UserIntent(IntentType.TEMPLATE_LIST, "t")) as Outcome.Success<Plan>).value
        assertEquals("template.list", list.steps[0].action)
        val del = (planner.plan(UserIntent(IntentType.TEMPLATE_DELETE, "t", mapOf("name" to "X"))) as Outcome.Success<Plan>).value
        assertEquals("template.delete", del.steps[0].action)
        val search = (planner.plan(UserIntent(IntentType.LIB_SEARCH, "t", mapOf("query" to "lut"))) as Outcome.Success<Plan>).value
        assertEquals("library.search", search.steps[0].action)
        val all = (planner.plan(UserIntent(IntentType.LIB_SEARCH, "t")) as Outcome.Success<Plan>).value
        assertEquals("library.list", all.steps[0].action)
        val noname = planner.plan(UserIntent(IntentType.TEMPLATE_SAVE, "t"))
        assertEquals("PLAN_NO_NAME", (noname as Outcome.Failure).error.code)
    }

    @Test
    fun cp81ColorLutPlansRealSteps() = runBlocking {
        val auto = (planner.plan(UserIntent(IntentType.COLOR_AUTO, "t", mapOf("clipIndex" to "1"))) as Outcome.Success<Plan>).value
        assertEquals("timeline.colorAuto", auto.steps[0].action)
        val lut = (planner.plan(UserIntent(IntentType.LUT_SET, "t", mapOf("clipIndex" to "1", "path" to "w.cube"))) as Outcome.Success<Plan>).value
        assertEquals("timeline.lut", lut.steps[0].action)
        val clear = (planner.plan(UserIntent(IntentType.LUT_CLEAR, "t", mapOf("clipIndex" to "1"))) as Outcome.Success<Plan>).value
        assertEquals("timeline.lutClear", clear.steps[0].action)
        val nofile = planner.plan(UserIntent(IntentType.LUT_SET, "t", mapOf("clipIndex" to "1")))
        assertEquals("PLAN_NO_FILE", (nofile as Outcome.Failure).error.code)
    }

    @Test
    fun cp80TrackStabPlansRealSteps() = runBlocking {
        val track = (planner.plan(UserIntent(IntentType.TRACK, "t", mapOf("clipIndex" to "1", "target" to "text:2"))) as Outcome.Success<Plan>).value
        assertEquals("timeline.track", track.steps[0].action)
        assertEquals("text:2", track.steps[0].args["target"])
        val stab = (planner.plan(UserIntent(IntentType.STABILIZE, "t", mapOf("clipIndex" to "1"))) as Outcome.Success<Plan>).value
        assertEquals("timeline.stabilize", stab.steps[0].action)
        val missing = planner.plan(UserIntent(IntentType.STABILIZE, "t"))
        assertEquals("PLAN_NO_CLIP", (missing as Outcome.Failure).error.code)
    }

    @Test
    fun cp79MaskChromaBgPlansRealSteps() = runBlocking {
        val mask = (planner.plan(UserIntent(IntentType.CLIP_MASK, "t", mapOf("clipIndex" to "1", "shape" to "ellipse"))) as Outcome.Success<Plan>).value
        assertEquals("timeline.setMask", mask.steps[0].action)
        val chroma = (planner.plan(UserIntent(IntentType.CLIP_CHROMA, "t", mapOf("clipIndex" to "1"))) as Outcome.Success<Plan>).value
        assertEquals("timeline.setChroma", chroma.steps[0].action)
        val bg = (planner.plan(UserIntent(IntentType.BG_SET, "t", mapOf("mode" to "blur"))) as Outcome.Success<Plan>).value
        assertEquals("timeline.setBackground", bg.steps[0].action)
        assertEquals("blur", bg.steps[0].args["mode"])
        val missing = planner.plan(UserIntent(IntentType.CLIP_MASK, "t"))
        assertEquals("PLAN_NO_CLIP", (missing as Outcome.Failure).error.code)
    }

    @Test
    fun cp78ColorScopesPlansRealSteps() = runBlocking {
        val color = (planner.plan(UserIntent(IntentType.CLIP_COLOR, "t", mapOf("clipIndex" to "1", "preset" to "bw"))) as Outcome.Success<Plan>).value
        assertEquals("timeline.setColor", color.steps[0].action)
        assertEquals("bw", color.steps[0].args["preset"])
        val missing = planner.plan(UserIntent(IntentType.CLIP_COLOR, "t", mapOf("clipIndex" to "1")))
        assertEquals("PLAN_NO_COLOR", (missing as Outcome.Failure).error.code)
        val scopes = (planner.plan(UserIntent(IntentType.IMAGE_SCOPES, "t", mapOf("path" to "a.png"))) as Outcome.Success<Plan>).value
        assertEquals("image", scopes.steps[0].toolId)
        assertEquals("scopes", scopes.steps[0].action)
        val noPath = planner.plan(UserIntent(IntentType.IMAGE_SCOPES, "t"))
        assertEquals("PLAN_NO_PATH", (noPath as Outcome.Failure).error.code)
    }

    @Test
    fun cp77TransitionFxPlansRealSteps() = runBlocking {
        val tr = (planner.plan(UserIntent(IntentType.TRANSITION_SET, "t", mapOf("clipIndex" to "2", "edge" to "in", "kind" to "dissolve"))) as Outcome.Success<Plan>).value
        assertEquals("timeline.setTransition", tr.steps[0].action)
        assertEquals("dissolve", tr.steps[0].args["kind"])
        val fx = (planner.plan(UserIntent(IntentType.CLIP_FX, "t", mapOf("clipIndex" to "1", "grain" to "20"))) as Outcome.Success<Plan>).value
        assertEquals("timeline.setFx", fx.steps[0].action)
        assertEquals("20", fx.steps[0].args["grain"])
        val missing = planner.plan(UserIntent(IntentType.CLIP_FX, "t", mapOf("clipIndex" to "1")))
        assertEquals("PLAN_NO_FX", (missing as Outcome.Failure).error.code)
    }

    @Test
    fun cp76KeyframePlansRealSteps() = runBlocking {
        val set = (planner.plan(UserIntent(IntentType.KEYFRAME_SET, "t", mapOf("clipIndex" to "1", "prop" to "scale", "atMs" to "2000", "value" to "150"))) as Outcome.Success<Plan>).value
        assertEquals("timeline.setKeyframe", set.steps[0].action)
        assertEquals("150", set.steps[0].args["value"])
        val missing = planner.plan(UserIntent(IntentType.KEYFRAME_SET, "t", mapOf("clipIndex" to "1")))
        assertEquals("PLAN_NO_KEYPROP", (missing as Outcome.Failure).error.code)
        val clear = (planner.plan(UserIntent(IntentType.KEYFRAME_CLEAR, "t", mapOf("clipIndex" to "1"))) as Outcome.Success<Plan>).value
        assertEquals("timeline.clearKeyframes", clear.steps[0].action)
    }

    @Test
    fun cp74TextPlansRealSteps() = runBlocking {
        val add = (planner.plan(UserIntent(IntentType.TEXT_ADD, "t", mapOf("text" to "hi"))) as Outcome.Success<Plan>).value
        assertEquals("timeline.addText", add.steps[0].action)
        val idea = (planner.plan(UserIntent(IntentType.TEXT_IDEA, "t", mapOf("kind" to "hook", "topic" to "x"))) as Outcome.Success<Plan>).value
        assertEquals("text.ideas", idea.steps[0].action)
        val missing = planner.plan(UserIntent(IntentType.TEXT_ADD, "t"))
        assertEquals("PLAN_NO_TEXT", (missing as Outcome.Failure).error.code)
    }

    @Test
    fun cp73TransformPlansRealSteps() = runBlocking {
        val rotate = (planner.plan(UserIntent(IntentType.CLIP_ROTATE, "t", mapOf("clipIndex" to "1", "rotation" to "90"))) as Outcome.Success<Plan>).value
        assertEquals("timeline.transformClip", rotate.steps[0].action)
        assertEquals("90", rotate.steps[0].args["rotation"])
        val freeze = (planner.plan(UserIntent(IntentType.CLIP_FREEZE, "t", mapOf("clipIndex" to "1"))) as Outcome.Success<Plan>).value
        assertEquals("timeline.freezeFrame", freeze.steps[0].action)
        val missing = planner.plan(UserIntent(IntentType.CLIP_ROTATE, "t"))
        assertEquals("PLAN_NO_CLIP", (missing as Outcome.Failure).error.code)
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
