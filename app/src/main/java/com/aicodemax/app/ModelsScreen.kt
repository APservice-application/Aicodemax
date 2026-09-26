package com.aicodemax.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.aicodemax.ai.models.InstallState
import com.aicodemax.ai.models.JavaNetLlmTransport
import com.aicodemax.ai.models.LocalEndpointDetector
import com.aicodemax.ai.models.ModelDescriptor
import com.aicodemax.ai.models.ModelRequirement
import com.aicodemax.ai.models.OpenAiCompatProvider
import com.aicodemax.ai.models.ScoringModelRouter
import com.aicodemax.core.common.fold
import com.aicodemax.core.resources.ResourceSnapshot
import com.aicodemax.ui.designsystem.LocalSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Models Center (CP-38): registry + install/download/health + scored recommendation. */
@Composable
fun ModelsScreen(services: ServiceLocator) {
    val spacing = LocalSpacing.current
    val scope = rememberCoroutineScope()
    var resourceText by remember { mutableStateOf("กำลังอ่าน…") }
    var snapshot by remember { mutableStateOf<ResourceSnapshot?>(null) }
    var models by remember { mutableStateOf(services.models.all()) }
    var verdict by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    // CP-59 advanced/self-hosted endpoint (memory-only; cloud providers use BYOK panel above).
    var llmUrl by remember { mutableStateOf("http://127.0.0.1:8080/v1") }
    var llmKey by remember { mutableStateOf("") }
    var llmModel by remember { mutableStateOf("gpt-4o-mini") }
    var llmStatus by remember { mutableStateOf("ยังไม่เชื่อมต่อ") }
    var llmBusy by remember { mutableStateOf(false) }
    var advancedEndpointOpen by remember { mutableStateOf(false) }
    // CP-132: local runtime section (§35).
    val runtimeState by services.aiRuntime.state.collectAsState()
    val runtimeError by services.aiRuntime.error.collectAsState()
    // CP-144: built-in provision progress + refresh tick for the optional model row.
    val provisionProgress by services.aiRuntime.provisionProgress.collectAsState()
    var builtinTick by remember { mutableStateOf(0) }
    var runtimeBusy by remember { mutableStateOf(false) }
    var runtimeNote by remember { mutableStateOf<String?>(null) }
    var runtimePressure by remember { mutableStateOf<String?>(null) }

    fun refreshModels() {
        models = services.models.all()
        verdict = services.router.pick().fold(
            onSuccess = { "router เลือก: ${it.name} (${it.kind.name}/${it.status.name})\n" + services.brainRoute() },
            onFailure = { it.message + "\n" + services.brainRoute() },
        )
    }

    LaunchedEffect(Unit) {
        services.resources.snapshot().fold(
            onSuccess = { s ->
                snapshot = s
                resourceText = "RAM ว่าง ${s.ramAvailableBytes / 1024 / 1024}MB / " +
                    "${s.ramTotalBytes / 1024 / 1024}MB\n" +
                    "ดิสก์ว่าง ${s.storageAvailableBytes / 1024 / 1024}MB / " +
                    "${s.storageTotalBytes / 1024 / 1024}MB\n" +
                    "แบต ${s.batteryPercent}%${if (s.batteryCharging) " (ชาร์จ)" else ""}\n" +
                    "เน็ต: ${if (!s.networkAvailable) "ออฟไลน์" else if (s.networkUnmetered) "ไม่จำกัด" else "จำกัดปริมาณ"}"
            },
            onFailure = { resourceText = "อ่านไม่ได้: ${it.message}" },
        )
        refreshModels()
    }

    val scored = remember(models, snapshot) {
        if (models.isEmpty()) {
            "ยังไม่มีโมเดลที่ลงทะเบียน"
        } else {
            val router = ScoringModelRouter(
                services.models,
                resources = { snapshot },
                health = { id ->
                    services.installer.healthCheck(id).fold(
                        onSuccess = { it.healthy },
                        onFailure = { false },
                    )
                },
            )
            router.pick(ModelRequirement("chat")).fold(
                onSuccess = { "แนะนำ: ${it.name} (${it.kind.name})" },
                onFailure = { it.message },
            )
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        item(key = "__runtime__") {
            Text("AI ในตัว (มากับแอป)", style = MaterialTheme.typography.titleMedium)
            val builtinLabel = if (services.aiRuntime.isBuiltinActive()) "AicodeMax Built-in" else "โมเดลเสริม"
            Text(
                "$builtinLabel • " + when (runtimeState) {
                    com.aicodemax.ai.runtime.AiRuntimeState.READY -> "พร้อมใช้ ✅"
                    com.aicodemax.ai.runtime.AiRuntimeState.GENERATING -> "กำลังตอบ…"
                    com.aicodemax.ai.runtime.AiRuntimeState.LOADING_MODEL,
                    com.aicodemax.ai.runtime.AiRuntimeState.INITIALIZING,
                    -> {
                        val progress = provisionProgress
                        if (progress != null) "กำลังเตรียม AI ครั้งแรก… ${(progress * 100).toInt()}%"
                        else "กำลังเตรียม… (${runtimeState.name})"
                    }
                    com.aicodemax.ai.runtime.AiRuntimeState.OFFLINE -> "ออฟไลน์: ${runtimeError ?: "—"}"
                    com.aicodemax.ai.runtime.AiRuntimeState.ERROR -> "ผิดพลาด: ${runtimeError ?: "ไม่ทราบสาเหตุ"}"
                    else -> runtimeState.name
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            // CP-144: optional-model row (the built-in AI never needs downloads).
            val optionalProfile = remember(builtinTick) {
                services.modelManager.find("qwen2.5-1.5b-q4_k_m")
            }
            val optionalReady = remember(builtinTick, optionalProfile) {
                optionalProfile != null &&
                    services.modelManager.status(optionalProfile) is com.aicodemax.ai.runtime.ModelInstallStatus.Ready
            }
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                TextButton(
                    enabled = !runtimeBusy,
                    onClick = {
                        scope.launch {
                            runtimeBusy = true
                            runtimePressure = services.aiRuntime.pressure()?.name?.let { "แรงดันหน่วยความจำ: $it" }
                            withContext(Dispatchers.IO) { services.aiRuntime.recover().join() }
                            runtimeNote = "กู้คืนแล้ว — ${services.aiRuntime.state.value.name}"
                            builtinTick++
                            runtimeBusy = false
                        }
                    },
                ) { Text("กู้คืน") }
                if (optionalProfile != null && !optionalReady) {
                    TextButton(
                        enabled = !runtimeBusy,
                        onClick = {
                            scope.launch {
                                runtimeBusy = true
                                runtimeNote = "กำลังโหลดโมเดลเสริม…"
                                val res = withContext(Dispatchers.IO) {
                                    services.modelManager.install(optionalProfile) { done, total ->
                                        runtimeNote = if (total != null && total > 0) {
                                            "โหลดเสริม ${done * 100 / total}%"
                                        } else {
                                            "โหลดเสริม ${done / 1024 / 1024}MB"
                                        }
                                    }
                                }
                                res.fold(
                                    onSuccess = { runtimeNote = "โหลดเสร็จ — กด 'สลับไปใช้ 1.5B'" },
                                    onFailure = { runtimeNote = it.message },
                                )
                                builtinTick++
                                runtimeBusy = false
                            }
                        },
                    ) { Text("โหลดโมเดลเสริม 1.5B") }
                }
                if (optionalReady && services.aiRuntime.isBuiltinActive()) {
                    TextButton(
                        enabled = !runtimeBusy,
                        onClick = {
                            scope.launch {
                                runtimeBusy = true
                                withContext(Dispatchers.IO) {
                                    services.aiRuntime.switchModel("qwen2.5-1.5b-q4_k_m").join()
                                }
                                builtinTick++
                                runtimeBusy = false
                            }
                        },
                    ) { Text("สลับไปใช้ 1.5B") }
                }
                if (optionalReady && !services.aiRuntime.isBuiltinActive()) {
                    TextButton(
                        enabled = !runtimeBusy,
                        onClick = {
                            scope.launch {
                                runtimeBusy = true
                                withContext(Dispatchers.IO) {
                                    services.aiRuntime.loadBuiltin(services.builtinModelFile.path).join()
                                }
                                builtinTick++
                                runtimeBusy = false
                            }
                        },
                    ) { Text("กลับมาใช้ AI ในตัว") }
                }
            }
            runtimePressure?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            }
            runtimeNote?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
        item(key = "__hosted_byok__") {
            HostedProvidersPanel(services, onRoutesChanged = { refreshModels() })
        }
        item(key = "__head__") {
            Text("โมเดล (${models.size})", style = MaterialTheme.typography.titleMedium)
        }
        if (models.isEmpty()) {
            item(key = "__empty__") {
                Text("ยังไม่มีโมเดล — ลงทะเบียนผ่าน AI setup ในอนาคต / ติดตั้งจาก URL ด้านล่างเมื่อมี model id")
            }
        }
        items(models, key = { it.id }) { model ->
            ModelCard(
                model = model,
                progressLine = progressLine(services, model.id),
                onHealth = {
                    scope.launch {
                        working = true
                        services.installer.healthCheck(model.id).fold(
                            onSuccess = { note = "${model.name}: ${it.detail}" },
                            onFailure = { note = it.message },
                        )
                        refreshModels()
                        working = false
                    }
                },
                onPause = { services.installer.pause(model.id) },
                onResume = { services.installer.resume(model.id) },
                onCancel = { services.installer.cancel(model.id) },
            )
        }
        item(key = "__install__") {
            Text("ติดตั้งจาก URL", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                TextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("https://…/model.bin (ต้องมี model id ก่อน)") },
                )
            }
            Text(
                "การติดตั้งต้องเลือกโมเดลที่ลงทะเบียนแล้ว — ฟีเจอร์ลงทะเบียนโมเดลใหม่ตามมาในรอบถัดไป",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        item(key = "__llm__") {
            TextButton(onClick = { advancedEndpointOpen = !advancedEndpointOpen }) {
                Text(if (advancedEndpointOpen) "ซ่อน: endpoint ส่วนตัว (ขั้นสูง)" else "ขั้นสูง: endpoint ส่วนตัว ▾")
            }
            if (advancedEndpointOpen) {
                TextField(
                    value = llmUrl,
                    onValueChange = { llmUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("base URL เช่น https://api.openai.com/v1") },
                )
                TextField(
                    value = llmKey,
                    onValueChange = { llmKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    placeholder = { Text("API key (ถ้ามี) — อยู่ในหน่วยความจำเท่านั้น") },
                )
                TextField(
                    value = llmModel,
                    onValueChange = { llmModel = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("model id") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                llmBusy = true
                                val found = withContext(Dispatchers.IO) {
                                    LocalEndpointDetector.detect(JavaNetLlmTransport())
                                }
                                found.fold(
                                    onSuccess = {
                                        llmUrl = it.substringBefore(" — ")
                                        llmStatus = "พบ local: $it"
                                    },
                                    onFailure = { llmStatus = it.message },
                                )
                                llmBusy = false
                            }
                        },
                        enabled = !llmBusy,
                    ) { Text("ตรวจ localhost") }
                    TextButton(
                        onClick = {
                            scope.launch {
                                llmBusy = true
                                val provider = OpenAiCompatProvider(llmUrl.trim(), { llmKey.takeIf { it.isNotBlank() } })
                                val health = withContext(Dispatchers.IO) { provider.health() }
                                health.fold(
                                    onSuccess = {
                                        services.setLlm(provider, llmModel.trim(), llmUrl.trim())
                                        llmStatus = "เชื่อมต่อแล้ว: $it"
                                    },
                                    onFailure = { llmStatus = "ต่อไม่ได้: ${it.message}" },
                                )
                                llmBusy = false
                            }
                        },
                        enabled = !llmBusy,
                    ) { Text("เชื่อมต่อ") }
                    TextButton(onClick = {
                        services.setLlm(null, llmModel)
                        llmStatus = "ตัดการเชื่อมต่อแล้ว"
                    }) { Text("ตัด") }
                }
                Text(
                    "สถานะ: $llmStatus",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Text(
                    "สำหรับเซิร์ฟเวอร์ส่วนตัวแบบ OpenAI-compatible เท่านั้น; คีย์ส่วนนี้อยู่ในหน่วยความจำและหายเมื่อปิดแอป. บริการที่รองรับไม่ต้องกรอก URL/โมเดลเอง — ใช้ BYOK ด้านบน",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
        item(key = "__cloud__") {
            Text("Cloud (§29)", style = MaterialTheme.typography.titleMedium)
            Text(
                services.cloudStatus(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            Text(
                "ซับแปลด้วย LLM ได้แล้วเมื่อต่อ provider ด้านบน (แปลซับ ... ด้วย llm) ส่วน text2video/music/sfx รอ provider เฉพาะ",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        if (note != null) {
            item(key = "__note__") { Text(note!!, style = MaterialTheme.typography.bodyMedium) }
        }
        item(key = "__verdict__") {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(modifier = Modifier.fillMaxWidth().padding(spacing.md)) {
                    Text(verdict, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        scored,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }
        item(key = "__res__") {
            Text("ทรัพยากรเครื่อง", style = MaterialTheme.typography.titleMedium)
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
                Text(resourceText, modifier = Modifier.fillMaxWidth().padding(spacing.md))
            }
        }
    }
}

private fun progressLine(services: ServiceLocator, modelId: String): String {
    val progress = services.installer.progress(modelId)
    return when (progress.state) {
        InstallState.NOT_INSTALLED -> "ยังไม่ติดตั้ง"
        InstallState.DOWNLOADING -> "ดาวน์โหลด ${progress.bytesDone}/${progress.bytesTotal} bytes"
        InstallState.PAUSED -> "หยุดชั่วคราว (${progress.bytesDone} bytes)"
        InstallState.READY -> "พร้อมใช้"
        else -> progress.state.name + (if (progress.detail.isNotBlank()) " — ${progress.detail}" else "")
    }
}

@Composable
private fun ModelCard(
    model: ModelDescriptor,
    progressLine: String,
    onHealth: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(modifier = Modifier.fillMaxWidth().padding(spacing.md)) {
            Text("${model.name} — ${model.status.name}", style = MaterialTheme.typography.titleSmall)
            Text(
                "${model.kind.name}" + if (model.sizeBytes > 0) " • ${model.sizeBytes / 1024 / 1024}MB" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            Text(progressLine, style = MaterialTheme.typography.bodyMedium)
            if (model.statusDetail.isNotBlank()) {
                Text(
                    model.statusDetail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                TextButton(onClick = onHealth) { Text("ตรวจสุขภาพ") }
                TextButton(onClick = onPause) { Text("พัก") }
                TextButton(onClick = onResume) { Text("ต่อ") }
                TextButton(onClick = onCancel) { Text("ยกเลิก") }
            }
        }
    }
}
