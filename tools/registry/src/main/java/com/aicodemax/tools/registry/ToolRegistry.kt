package com.aicodemax.tools.registry

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import java.util.concurrent.ConcurrentHashMap

interface ToolRegistry {
    fun register(descriptor: ToolDescriptor): Outcome<ToolDescriptor>
    fun update(descriptor: ToolDescriptor): Outcome<ToolDescriptor>
    fun unregister(toolId: String): Boolean
    fun get(toolId: String): ToolDescriptor?
    fun all(): List<ToolDescriptor>
    fun runnable(): List<ToolDescriptor>
}

class InMemoryToolRegistry : ToolRegistry {
    private val tools = ConcurrentHashMap<String, ToolDescriptor>()

    override fun register(descriptor: ToolDescriptor): Outcome<ToolDescriptor> {
        val prev = tools.putIfAbsent(descriptor.toolId, descriptor)
        return if (prev == null) {
            Outcome.Success(descriptor)
        } else {
            Outcome.Failure(AppError("TOOL_EXISTS", "tool '${descriptor.toolId}' already registered"))
        }
    }

    override fun update(descriptor: ToolDescriptor): Outcome<ToolDescriptor> {
        return if (tools.replace(descriptor.toolId, descriptor) != null) {
            Outcome.Success(descriptor)
        } else {
            Outcome.Failure(AppError("TOOL_UNKNOWN", "tool '${descriptor.toolId}' is not registered"))
        }
    }

    override fun unregister(toolId: String): Boolean = tools.remove(toolId) != null
    override fun get(toolId: String): ToolDescriptor? = tools[toolId]
    override fun all(): List<ToolDescriptor> = tools.values.sortedBy { it.toolId }
    override fun runnable(): List<ToolDescriptor> = all().filter { it.isRunnable() }
}
