package com.aicodemax.core.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * CP-47: cross-tool context — the working set every tool reads: which project,
 * which conversation, which files, and an optional prompt handed from another
 * tool (e.g. browser → chat). Tools never guess context; they read this.
 */
data class WorkingSet(
    val projectId: String? = null,
    val conversationId: String? = null,
    val selectedFiles: List<String> = emptyList(),
    val pendingPrompt: String? = null,
)

interface WorkingSetStore {
    val workingSet: StateFlow<WorkingSet>
    fun setProject(projectId: String?)
    fun setConversation(conversationId: String?)
    fun setSelectedFiles(paths: List<String>)
    fun handPrompt(prompt: String)
    fun consumePrompt(): String?
}

class InMemoryWorkingSetStore(initial: WorkingSet = WorkingSet()) : WorkingSetStore {
    private val _workingSet = MutableStateFlow(initial)
    override val workingSet: StateFlow<WorkingSet> = _workingSet.asStateFlow()

    override fun setProject(projectId: String?) {
        _workingSet.update { it.copy(projectId = projectId) }
    }

    override fun setConversation(conversationId: String?) {
        _workingSet.update { it.copy(conversationId = conversationId) }
    }

    override fun setSelectedFiles(paths: List<String>) {
        _workingSet.update { it.copy(selectedFiles = paths) }
    }

    override fun handPrompt(prompt: String) {
        _workingSet.update { it.copy(pendingPrompt = prompt) }
    }

    override fun consumePrompt(): String? {
        val prompt = _workingSet.value.pendingPrompt
        if (prompt != null) _workingSet.update { it.copy(pendingPrompt = null) }
        return prompt
    }
}
