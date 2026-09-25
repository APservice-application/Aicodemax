package com.aicodemax.ai.runtime

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * CP-126 (spec Phase 8 + §20–22): lifecycle owner of local AI.
 *
 * States (§20): UNINITIALIZED → INITIALIZING → LOADING_MODEL → READY ⇄
 * GENERATING, plus STOPPING / UNLOADING / ERROR / RECOVERING / OFFLINE.
 *
 * - [initialize] runs fully in the background (§21 — never block UI/startup).
 * - Native crashes can't be caught in-process; [SessionMarker] detects an
 *   unclean previous run so the UI can offer recovery (§22).
 * - CP-144 (BUILT-IN AI): when [expectBuiltin] is true, first launch does
 *   NOT touch [ModelManager] — the app provisioner copies the bundled asset
 *   and calls [loadBuiltin]. ModelManager stays for OPTIONAL models only.
 */
enum class AiRuntimeState {
    UNINITIALIZED,
    INITIALIZING,
    READY,
    LOADING_MODEL,
    GENERATING,
    STOPPING,
    UNLOADING,
    ERROR,
    RECOVERING,
    OFFLINE,
}

class AiRuntimeManager(
    private val scope: CoroutineScope,
    private val runtime: AiRuntime,
    private val models: ModelManager,
    private val runtimeDir: String,
    private val loadOpts: LoadOpts = LoadOpts(),
    private val template: (List<ChatMessage>) -> String = ChatTemplate::qwen3,
    private val resources: ResourceManager? = null,
    private val expectBuiltin: Boolean = false,
) {
    private val _state = MutableStateFlow(AiRuntimeState.UNINITIALIZED)
    val state: StateFlow<AiRuntimeState> = _state

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /**
     * CP-144: first-launch provision progress (0f..1f while the bundled
     * asset is copied into place, null otherwise). Local copy — never a
     * network download.
     */
    private val _provisionProgress = MutableStateFlow<Float?>(null)
    val provisionProgress: StateFlow<Float?> = _provisionProgress

    fun setProvisionProgress(progress: Float?) {
        _provisionProgress.value = progress
    }

    /** Path of the loaded built-in model file (null when on an optional model). */
    @Volatile
    private var builtinPath: String? = null

    fun isBuiltinActive(): Boolean = builtinPath != null && runtime.isModelLoaded()

    /** True when the previous run left an unclean session marker (§22). */
    @Volatile
    var crashedLastRun: Boolean = false
        private set

    /** Background init: built-in first (or legacy ModelManager path). */
    fun initialize(): Job = scope.launch(Dispatchers.IO) {
        if (_state.value != AiRuntimeState.UNINITIALIZED) return@launch
        _state.value = AiRuntimeState.INITIALIZING
        _error.value = null
        crashedLastRun = SessionMarker.begin(runtimeDir)
        // CP-144: built-in AI owns first launch (§7 — no ModelManager
        // dependency). The provisioner copies the bundled asset, then calls
        // loadBuiltin(). Dev builds without the asset call builtinMissing().
        if (expectBuiltin) return@launch
        loadFromModelManager()
    }

    /** Dev-build fallback: no bundled asset — use the legacy optional path honestly. */
    fun builtinMissing() {
        if (_state.value != AiRuntimeState.INITIALIZING) return
        loadFromModelManager()
    }

    fun provisionFailed(reason: String) {
        _provisionProgress.value = null
        _error.value = reason
        _state.value = AiRuntimeState.ERROR
    }

    /**
     * CP-144: load the built-in model file directly (never via ModelManager).
     * Validates GGUF + size, applies the RAM gate, then loads.
     */
    fun loadBuiltin(path: String): Job = scope.launch(Dispatchers.IO) {
        if (_state.value == AiRuntimeState.GENERATING || _state.value == AiRuntimeState.LOADING_MODEL) return@launch
        _state.value = AiRuntimeState.INITIALIZING
        _error.value = null
        val file = File(path)
        if (!file.isFile || file.length() < 100_000_000L) {
            _error.value = "ไฟล์ AI ในตัวไม่สมบูรณ์ (${file.length()} bytes)"
            _state.value = AiRuntimeState.ERROR
            return@launch
        }
        val info = try {
            Gguf.read(file)
        } catch (e: Exception) {
            _error.value = "ไฟล์ AI ในตัวใช้ไม่ได้: ${e.message}"
            _state.value = AiRuntimeState.ERROR
            return@launch
        }
        builtinPath = file.path
        loadWithResources(ModelInstallStatus.Ready(file.path, file.length(), info))
    }

    private fun loadFromModelManager() {
        val active = models.active()
        if (active == null) {
            setOff("ไม่มีโมเดลใน catalog")
            return
        }
        when (val st = models.status(active)) {
            is ModelInstallStatus.Missing -> setOff("โมเดล ${active.id} ยังไม่ติดตั้ง — ใช้ ModelManager.install ก่อน")
            is ModelInstallStatus.Invalid -> {
                _error.value = st.reason
                _state.value = AiRuntimeState.ERROR
            }
            is ModelInstallStatus.Ready -> loadWithResources(st)
        }
    }

    /** CP-127: RAM gate — Ok / Degrade (smaller ctx) / Refuse (OFFLINE). */
    private fun loadWithResources(ready: ModelInstallStatus.Ready) {
        val rm = resources
        if (rm == null) {
            loadIntoRuntime(ready.path, loadOpts)
            return
        }
        when (val verdict = rm.canLoad(ready.bytes, loadOpts)) {
            is LoadVerdict.Ok -> loadIntoRuntime(ready.path, verdict.opts)
            is LoadVerdict.Degrade -> {
                _error.value = verdict.reason
                loadIntoRuntime(ready.path, verdict.opts)
            }
            is LoadVerdict.Refuse -> setOff(verdict.reason)
        }
    }

    /** Current memory-pressure level (§19); null when no reader is wired. */
    fun pressure(): Pressure? = resources?.pressure()

    private fun setOff(reason: String) {
        _error.value = reason
        _state.value = AiRuntimeState.OFFLINE
    }

    private fun loadIntoRuntime(path: String, opts: LoadOpts = loadOpts) {
        _state.value = AiRuntimeState.LOADING_MODEL
        runtime.loadModel(path, opts).fold(
            onSuccess = {
                _error.value = null
                _state.value = AiRuntimeState.READY
            },
            onFailure = {
                _error.value = it.message
                _state.value = AiRuntimeState.ERROR
            },
        )
    }

    /** Chat (must be READY; runs generation on IO, state returns to READY). */
    suspend fun chat(
        messages: List<ChatMessage>,
        params: GenParams = GenParams(),
        onToken: TokenSink = TokenSink {},
    ): Outcome<ChatReply> {
        if (_state.value != AiRuntimeState.READY) {
            return Outcome.Failure(AppError("AI_STATE", "AI ยังไม่พร้อม (state=${_state.value})"))
        }
        if (messages.none { it.role == ChatRole.USER || it.role == ChatRole.TOOL }) {
            return Outcome.Failure(AppError("AI_STATE", "missing user message"))
        }
        _state.value = AiRuntimeState.GENERATING
        return try {
            withContext(Dispatchers.IO) {
                runtime.generate(template(messages), params, onToken).fold(
                    onSuccess = {
                        _state.value = AiRuntimeState.READY
                        // CP-147: Qwen3 must never leak raw <think> into chat.
                        Outcome.Success(ChatReply(ChatTemplate.stripThinking(it.text), it.stoppedEarly, via = "local"))
                    },
                    onFailure = {
                        _error.value = it.message
                        _state.value = AiRuntimeState.ERROR
                        Outcome.Failure(it)
                    },
                )
            }
        } catch (e: Exception) {
            _error.value = e.message ?: e.javaClass.simpleName
            _state.value = AiRuntimeState.ERROR
            Outcome.Failure(AppError("AI_STATE", _error.value ?: "chat failed"))
        }
    }

    fun stopGeneration() {
        if (_state.value == AiRuntimeState.GENERATING) {
            _state.value = AiRuntimeState.STOPPING
            try {
                runtime.stopGeneration()
            } finally {
                _state.value = if (runtime.isModelLoaded()) AiRuntimeState.READY else AiRuntimeState.ERROR
            }
        }
    }

    /** Switch to another installed (optional) model (background unload + load). */
    fun switchModel(id: String): Job = scope.launch(Dispatchers.IO) {
        if (_state.value == AiRuntimeState.GENERATING || _state.value == AiRuntimeState.LOADING_MODEL) {
            _error.value = "กำลังทำงานอยู่ — รอให้เสร็จก่อนค่อยสลับโมเดล"
            return@launch
        }
        when (val switched = models.setActive(id)) {
            is Outcome.Failure -> {
                _error.value = switched.error.message
                return@launch
            }
            is Outcome.Success -> {
                _state.value = AiRuntimeState.UNLOADING
                runtime.unloadModel()
                builtinPath = null
                val profile = switched.value
                val file = models.fileFor(profile)
                loadIntoRuntime(file.path)
            }
        }
    }

    /**
     * CP-128 first-run delivery: download the active (or default) model, then
     * activate and load it. Safe to call from UI (all work in background).
     * CP-144: kept for OPTIONAL models only — the built-in AI never needs this.
     */
    fun installActiveModel(onProgress: (done: Long, total: Long?) -> Unit = { _, _ -> }): Job =
        scope.launch(Dispatchers.IO) {
            if (_state.value == AiRuntimeState.GENERATING || _state.value == AiRuntimeState.LOADING_MODEL) return@launch
            val target = models.active()
                ?: models.list().firstOrNull { it.pack == ModelPack.DEFAULT }
            if (target == null) {
                _error.value = "ไม่มีโมเดลใน catalog"
                return@launch
            }
            _state.value = AiRuntimeState.INITIALIZING
            _error.value = null
            when (val res = models.install(target, onProgress)) {
                is Outcome.Failure -> {
                    _error.value = res.error.message
                    _state.value = AiRuntimeState.OFFLINE
                }
                is Outcome.Success -> {
                    models.setActive(target.id)
                    when (val st = models.status(target)) {
                        is ModelInstallStatus.Ready -> loadWithResources(st)
                        is ModelInstallStatus.Missing -> setOff("ติดตั้งแล้วแต่ไม่เจอไฟล์")
                        is ModelInstallStatus.Invalid -> {
                            _error.value = st.reason
                            _state.value = AiRuntimeState.ERROR
                        }
                    }
                }
            }
        }

    /** Attempt recovery from ERROR: unload + reload the current model. */
    fun recover(): Job = scope.launch(Dispatchers.IO) {
        if (_state.value != AiRuntimeState.ERROR && _state.value != AiRuntimeState.OFFLINE) return@launch
        if (resources?.pressure() == Pressure.CRITICAL) {
            _error.value = "RAM วิกฤต — ปิดแอปอื่นก่อนแล้วค่อย recover"
            return@launch
        }
        _state.value = AiRuntimeState.RECOVERING
        _error.value = null
        try {
            runtime.unloadModel()
        } catch (_: Exception) {
        }
        // CP-144: built-in reloads directly; optional models use the legacy path.
        val builtin = builtinPath
        if (builtin != null) {
            val file = File(builtin)
            if (!file.isFile) {
                builtinPath = null
                setOff("ไฟล์ AI ในตัวหายไป — ติดตั้งแอปใหม่")
                return@launch
            }
            loadIntoRuntime(file.path)
            return@launch
        }
        val active = models.active()
        if (active == null) {
            setOff("ไม่มีโมเดลใน catalog")
            return@launch
        }
        when (val st = models.status(active)) {
            is ModelInstallStatus.Ready -> loadIntoRuntime(st.path)
            is ModelInstallStatus.Missing -> setOff("โมเดล ${active.id} หายไป — ติดตั้งใหม่")
            is ModelInstallStatus.Invalid -> {
                _error.value = st.reason
                _state.value = AiRuntimeState.ERROR
            }
        }
    }

    /** Clean shutdown (§22): unload + mark the session clean. */
    fun shutdown(): Job = scope.launch(Dispatchers.IO) {
        _state.value = AiRuntimeState.UNLOADING
        try {
            runtime.stopGeneration()
            runtime.unloadModel()
        } catch (_: Exception) {
        } finally {
            SessionMarker.endClean(runtimeDir)
            _state.value = AiRuntimeState.UNINITIALIZED
        }
    }
}

/**
 * Crash detector (§22): a tiny marker file written at init start and marked
 * clean only on orderly shutdown. A native crash kills the process without
 * running shutdown, so the stale marker proves the previous run died.
 */
object SessionMarker {
    private const val NAME = "session.json"

    /** Returns true when the previous run did NOT shut down cleanly. */
    fun begin(runtimeDir: String): Boolean {
        val dir = File(runtimeDir).apply { mkdirs() }
        val file = File(dir, NAME)
        val crashed = file.isFile && !file.readText().contains("\"clean\":true")
        file.writeText("{\"clean\":false,\"startedAt\":${System.currentTimeMillis()}}")
        return crashed
    }

    fun endClean(runtimeDir: String) {
        val dir = File(runtimeDir).apply { mkdirs() }
        File(dir, NAME).writeText("{\"clean\":true,\"endedAt\":${System.currentTimeMillis()}}")
    }
}
