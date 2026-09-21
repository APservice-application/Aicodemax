package com.aicodemax.tools.editor

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import com.aicodemax.tools.files.FilePort
import com.aicodemax.tools.registry.CapabilityLayer
import com.aicodemax.tools.registry.CapabilityStatus
import com.aicodemax.tools.registry.LayerCapability
import com.aicodemax.tools.registry.ToolDescriptor

data class EditorBuffer(
    val path: String,
    val content: String,
    val dirty: Boolean,
)

interface EditorPort {
    fun descriptor(): ToolDescriptor
    suspend fun open(relativePath: String): Outcome<EditorBuffer>
    suspend fun setContent(relativePath: String, content: String): Outcome<EditorBuffer>
    suspend fun save(relativePath: String): Outcome<Long>
    suspend fun close(relativePath: String)
}

/** Real edit buffer backed by the files tool (full editor UI lands in Phase 24). */
class FileBackedEditor(private val files: FilePort) : EditorPort {
    private val buffers = mutableMapOf<String, EditorBuffer>()

    override fun descriptor(): ToolDescriptor = editorDescriptorToday()

    @Synchronized
    private fun getBuffer(path: String): EditorBuffer? = buffers[path]

    @Synchronized
    private fun putBuffer(buffer: EditorBuffer) {
        buffers[buffer.path] = buffer
    }

    @Synchronized
    private fun removeBuffer(path: String) {
        buffers.remove(path)
    }

    override suspend fun open(relativePath: String): Outcome<EditorBuffer> {
        getBuffer(relativePath)?.let { return Outcome.Success(it) }
        return when (val exists = files.exists(relativePath)) {
            is Outcome.Failure -> exists
            is Outcome.Success -> {
                if (!exists.value) {
                    val buffer = EditorBuffer(relativePath, "", dirty = true)
                    putBuffer(buffer)
                    Outcome.Success(buffer)
                } else {
                    when (val read = files.read(relativePath)) {
                        is Outcome.Failure -> read
                        is Outcome.Success -> {
                            val buffer = EditorBuffer(relativePath, read.value, dirty = false)
                            putBuffer(buffer)
                            Outcome.Success(buffer)
                        }
                    }
                }
            }
        }
    }

    override suspend fun setContent(relativePath: String, content: String): Outcome<EditorBuffer> {
        val opened = open(relativePath)
        if (opened is Outcome.Failure) return opened
        val buffer = (opened as Outcome.Success).value.copy(content = content, dirty = true)
        putBuffer(buffer)
        return Outcome.Success(buffer)
    }

    override suspend fun save(relativePath: String): Outcome<Long> {
        val buffer = getBuffer(relativePath)
            ?: return Outcome.Failure(AppError("EDITOR_NOT_OPEN", "'$relativePath' is not open"))
        return when (val written = files.write(relativePath, buffer.content)) {
            is Outcome.Failure -> written
            is Outcome.Success -> {
                putBuffer(buffer.copy(dirty = false))
                Outcome.Success(written.value)
            }
        }
    }

    override suspend fun close(relativePath: String) {
        removeBuffer(relativePath)
    }
}

fun editorDescriptorToday(): ToolDescriptor = ToolDescriptor(
    toolId = "editor",
    displayName = "Code Editor",
    version = "0.1.0",
    layers = listOf(
        LayerCapability(CapabilityLayer.UI, CapabilityStatus.MISSING, "editor UI in Phase 24"),
        LayerCapability(CapabilityLayer.CONTROLLER, CapabilityStatus.AVAILABLE, "EditorPort"),
        LayerCapability(CapabilityLayer.CAPABILITY_API, CapabilityStatus.AVAILABLE, "EditorPort"),
        LayerCapability(
            CapabilityLayer.RUNTIME, CapabilityStatus.AVAILABLE,
            "FileBackedEditor buffer over files tool",
        ),
        LayerCapability(CapabilityLayer.EXECUTION, CapabilityStatus.AVAILABLE, "via ToolGateway"),
        LayerCapability(
            CapabilityLayer.VERIFICATION, CapabilityStatus.PARTIAL,
            "save verified by files tool read-back",
        ),
        LayerCapability(CapabilityLayer.RECOVERY, CapabilityStatus.MISSING, "undo history later"),
    ),
)
