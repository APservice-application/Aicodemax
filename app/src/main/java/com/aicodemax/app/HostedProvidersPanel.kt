package com.aicodemax.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicodemax.ai.models.HostedCapability
import com.aicodemax.ai.models.HostedKeyRef
import com.aicodemax.ai.models.HostedModelCatalog
import com.aicodemax.ai.models.HostedProviderDirectory
import com.aicodemax.core.common.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Key-only setup: allowlisted endpoints, live searchable model list, explicit online/billing consent. */
@Composable
fun HostedProvidersPanel(services: ServiceLocator, onRoutesChanged: () -> Unit = {}) {
    val manager = services.hostedBrain
    val lastAnsweredBy by manager.lastSuccess.collectAsState()
    val scope = rememberCoroutineScope()
    var providerId by remember { mutableStateOf(manager.primaryProvider() ?: "gemini") }
    var keyInput by remember { mutableStateOf("") }
    var currentKeys by remember { mutableStateOf(manager.storedKeys(providerId)) }
    var catalog by remember { mutableStateOf(manager.cached(providerId)) }
    var selectedModel by remember { mutableStateOf(manager.selectedModel(providerId)) }
    var consent by remember { mutableStateOf(manager.consent()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("เพิ่มคีย์แล้วแอปจะดึงรายชื่อโมเดลโดยอัตโนมัติ") }
    var providerMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf<HostedCapability?>(null) }
    var query by remember { mutableStateOf("") }
    var modelPage by remember { mutableStateOf(0) }

    fun updateUi() {
        currentKeys = manager.storedKeys(providerId)
        selectedModel = manager.selectedModel(providerId)
        consent = manager.consent()
        catalog = manager.cached(providerId)
        services.syncHostedBrain()
        onRoutesChanged()
    }

    fun refresh() {
        val targetProvider = providerId
        scope.launch {
            busy = true
            try {
                val result = withContext(Dispatchers.IO) { manager.discover(targetProvider) }
                if (providerId == targetProvider) {
                    status = when (result) {
                        is Outcome.Success -> "พบ ${result.value.models.size} รุ่น — ${result.value.note}"
                        is Outcome.Failure -> "ดึงรายชื่อไม่สำเร็จ: ${result.error.message}"
                    }
                    updateUi()
                }
            } finally { busy = false }
        }
    }

    LaunchedEffect(providerId) {
        updateUi()
        if (currentKeys.isNotEmpty() && manager.cached(providerId) == null) {
            busy = true
            try {
                val result = withContext(Dispatchers.IO) { manager.discover(providerId) }
                status = when (result) {
                    is Outcome.Success -> "พบ ${result.value.models.size} รุ่น — ${result.value.note}"
                    is Outcome.Failure -> result.error.message
                }
                updateUi()
            } finally { busy = false }
        }
    }

    val selectedProvider = HostedProviderDirectory.find(providerId)
    val allModels = catalog?.models.orEmpty()
    val filtered = remember(catalog, filter, query) {
        allModels.filter { model ->
            (filter == null || filter in model.capabilities) &&
                (query.isBlank() || model.id.contains(query.trim(), ignoreCase = true) ||
                    model.name.contains(query.trim(), ignoreCase = true))
        }
    }
    val confirmed = allModels.find { it.id == selectedModel }
    val modelPageSize = 40
    val modelPages = ((filtered.size + modelPageSize - 1) / modelPageSize).coerceAtLeast(1)
    LaunchedEffect(catalog, filter, query, providerId) { modelPage = 0 }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text("สมอง AI ผ่าน API (BYOK)", style = MaterialTheme.typography.titleMedium)
        Text("รองรับ Google Gemini, OpenAI, Anthropic และอีก ${HostedProviderDirectory.all.size - 3} เจ้า " +
            "โดยไม่ต้องกรอก URL หรือชื่อโมเดลเอง; เพิ่มคีย์จะดึงรายการจาก API แต่จะไม่ส่งบทสนทนาก่อนยินยอม",
            style = MaterialTheme.typography.bodySmall)
        Box {
            OutlinedButton(onClick = { providerMenu = true }) {
                Text("ผู้ให้บริการ: ${selectedProvider?.name ?: providerId} ▾")
            }
            DropdownMenu(expanded = providerMenu, onDismissRequest = { providerMenu = false }) {
                HostedProviderDirectory.all.forEach { spec ->
                    DropdownMenuItem(text = {
                        Text("${spec.name} (${manager.storedKeys(spec.id).size} คีย์)")
                    }, onClick = {
                        providerId = spec.id
                        providerMenu = false
                        keyInput = ""
                        query = ""
                        filter = null
                    })
                }
            }
        }
        OutlinedTextField(
            value = keyInput,
            onValueChange = { keyInput = it },
            label = { Text("API key ของ ${selectedProvider?.name ?: "ผู้ให้บริการ"}") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val value = keyInput.trim()
                if (value.isBlank()) return@Button
                val targetProvider = providerId
                scope.launch {
                    busy = true
                    try {
                        withContext(Dispatchers.IO) { manager.addKey(targetProvider, value) }
                        keyInput = ""
                        updateUi()
                        val result = withContext(Dispatchers.IO) { manager.discover(targetProvider) }
                        if (providerId == targetProvider) {
                            status = when (result) {
                                is Outcome.Success -> "บันทึกคีย์แล้ว • พบ ${result.value.models.size} รุ่น; ยืนยันการส่งข้อมูลด้านล่าง"
                                is Outcome.Failure -> "บันทึกคีย์แล้ว แต่ยังดึงโมเดลไม่ได้: ${result.error.message}"
                            }
                        }
                        updateUi()
                    } catch (_: Exception) {
                        status = "บันทึกคีย์ไม่สำเร็จ (ตรวจคีย์/พื้นที่เก็บข้อมูลในเครื่อง)"
                    } finally {
                        busy = false
                    }
                }
            }, enabled = !busy && keyInput.isNotBlank()) { Text("เพิ่มคีย์") }
            TextButton(onClick = { refresh() }, enabled = !busy && currentKeys.isNotEmpty()) { Text("รีเฟรชโมเดล") }
        }
        Text("คีย์เก็บเข้ารหัสในเครื่องด้วย Android Keystore ไม่สำรองขึ้นคลาวด์; " +
            "หากล้างข้อมูลแอปต้องกรอกใหม่", style = MaterialTheme.typography.bodySmall)
        currentKeys.forEach { key: HostedKeyRef ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${selectedProvider?.name} • ${key.label}", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    manager.removeKey(key.id)
                    updateUi()
                    status = "ลบคีย์แล้ว — ยืนยันนโยบายอีกครั้งหากต้องการใช้ API"
                }) { Text("ลบ") }
            }
        }
        if (currentKeys.isNotEmpty()) {
            val isPrimary = manager.primaryProvider() == providerId
            TextButton(onClick = {
                try {
                    manager.selectPrimary(providerId)
                    updateUi()
                    status = "เปลี่ยนผู้ให้บริการหลักแล้ว — กรุณายืนยันการส่งข้อมูลอีกครั้ง"
                } catch (_: Exception) { status = "เลือกผู้ให้บริการหลักไม่สำเร็จ" }
            }, enabled = !isPrimary && selectedModel != null && !busy) {
                Text(if (isPrimary) "ผู้ให้บริการหลัก ✓" else "ตั้งเป็นผู้ให้บริการหลัก")
            }
        }
        if (catalog != null) {
            val info = catalog ?: HostedModelCatalog(emptyList(), false)
            Text("โมเดลที่ API ส่งกลับ: ${info.models.size} ${if (info.complete) "(ตามรายการที่ API เปิดเผย)" else "(รายชื่อไม่ครบ)"}",
                style = MaterialTheme.typography.bodyMedium)
            Text("ราคา/สิทธิ์ของแต่ละรุ่นอาจต่างกัน แม้มีคีย์ก็ไม่ได้แปลว่าใช้ฟรีหรือเรียกได้ทุกตัว",
                style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = query, onValueChange = { query = it },
                label = { Text("ค้นหาโมเดล") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            // The catalog includes every returned type; filtering is only for browsing.
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (listOf<HostedCapability?>(null) + HostedCapability.values().toList()).forEach { kind ->
                    FilterChip(selected = filter == kind, onClick = { filter = kind },
                        label = { Text(kind?.thaiLabel() ?: "ทั้งหมด", style = MaterialTheme.typography.labelSmall) })
                }
            }
            Box {
                OutlinedButton(onClick = { modelMenu = true }, enabled = !busy && filtered.isNotEmpty()) {
                    Text("โมเดล: ${selectedModel ?: "เลือกจาก ${filtered.size} รุ่น"} ▾",
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                    // Bounded page inside the actual dropdown: the catalog can contain
                    // thousands of entries; every page remains reachable without typing.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled = modelPage > 0, onClick = { modelPage-- }) { Text("ก่อนหน้า") }
                        Text("${modelPage + 1}/$modelPages", modifier = Modifier.padding(top = 12.dp))
                        TextButton(enabled = modelPage + 1 < modelPages, onClick = { modelPage++ }) { Text("ถัดไป") }
                    }
                    filtered.drop(modelPage * modelPageSize).take(modelPageSize).forEach { model ->
                        DropdownMenuItem(text = {
                            Column {
                                Text(model.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${model.id} • ${model.capabilities.joinToString { it.thaiLabel() }}",
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }, onClick = {
                            try {
                                manager.selectModel(providerId, model.id)
                                updateUi()
                                status = if (model.canTryChat) "เลือก ${model.name}; กรุณายืนยันนโยบายอีกครั้ง" else
                                    "เลือก ${model.name}; โมเดลนี้ยังใช้เป็นแชตของสมอง AI ไม่ได้"
                            } catch (_: Exception) { status = "เลือกโมเดลไม่สำเร็จ" }
                            modelMenu = false
                        })
                    }
                }
            }
            if (selectedModel != null && confirmed == null) {
                Text("โมเดลที่เคยเลือกไม่อยู่ในรายการที่ API ส่งกลับแล้ว — เลือกรุ่นใหม่ก่อนใช้",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (confirmed != null) {
                Text("ความสามารถ: ${confirmed.capabilities.joinToString { it.thaiLabel() }}" +
                    if (confirmed.canTryChat) {
                        if (HostedCapability.UNKNOWN in confirmed.capabilities) " • แชตยังไม่ยืนยันจาก API" else " • ใช้กับสมอง AI ได้"
                    } else " • ยังไม่มีตัวเรียกชนิดนี้ในสมอง AI",
                    style = MaterialTheme.typography.bodySmall)
                confirmed.priceNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Checkbox(checked = consent, onCheckedChange = { checked ->
                if (checked && manager.selectedRoute() == null) {
                    status = "เพิ่มคีย์และเลือกโมเดลที่ใช้เป็นแชตของสมอง AI ได้ก่อน"
                } else {
                    manager.setConsent(checked)
                    updateUi()
                }
            })
            Text("ยินยอมให้ส่งข้อความ ประวัติ บริบทงาน/ผลเครื่องมือที่จำเป็นไปผู้ให้บริการที่ตั้งค่าทั้งหมด " +
                "และสลับเจ้าเมื่อจำเป็น (API อาจคิดเงิน; ตรวจราคา/โควตาก่อน)", style = MaterialTheme.typography.bodySmall)
        }
        Text("ลำดับ fallback: ${manager.routeLabels().joinToString(" → ").ifBlank { "ยังไม่มีโมเดลที่เลือก" }}",
            style = MaterialTheme.typography.bodySmall)
        Text("API ที่ตอบล่าสุด: $lastAnsweredBy", style = MaterialTheme.typography.bodySmall)
        Text("หากคีย์ถูกปฏิเสธจะลองคีย์ถัดไป; เมื่อเจ้าเดิม 429 จะไม่หมุนคีย์เพื่อหลบโควตา " +
            "แต่ข้ามไปเจ้าอื่นที่คุณยินยอมไว้; timeout ไม่ยิงซ้ำเพื่อกันค่าใช้จ่ายซ้ำ",
            style = MaterialTheme.typography.bodySmall)
        Text("สถานะ: $status", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary)
    }
}

private fun HostedCapability.thaiLabel(): String = when (this) {
    HostedCapability.CHAT -> "สนทนา"
    HostedCapability.VISION -> "เข้าใจภาพ"
    HostedCapability.IMAGE -> "สร้างภาพ"
    HostedCapability.AUDIO -> "เสียง"
    HostedCapability.VIDEO -> "วิดีโอ"
    HostedCapability.EMBEDDING -> "เวกเตอร์"
    HostedCapability.RERANK -> "จัดอันดับ"
    HostedCapability.UNKNOWN -> "ยังไม่ระบุ"
}
