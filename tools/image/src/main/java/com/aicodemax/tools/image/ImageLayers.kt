package com.aicodemax.tools.image

/**
 * CP-142 image layers (§47): each layer is one image file plus display flags.
 * Order in [LayerStack.layers] is bottom → top; every layer is top-left
 * aligned on a canvas sized to the largest layer (see [ImageOps.flatten]).
 */
data class ImageLayer(
    val id: String,
    val name: String,
    val path: String,
    val visible: Boolean = true,
    val locked: Boolean = false,
)

/**
 * Immutable layer stack. Mutations use `require()` with Thai messages (same
 * style as [ImageOps]); callers surface them as honest UI messages.
 * Locked layers block rename/duplicate/delete/merge/path-update, but the
 * visibility toggle always stays available.
 */
data class LayerStack(
    val layers: List<ImageLayer> = emptyList(),
    val selectedId: String? = null,
) {
    /** Selected layer, falling back to the top layer when the id is stale. */
    val selected: ImageLayer?
        get() = layers.find { it.id == selectedId } ?: layers.lastOrNull()

    fun add(layer: ImageLayer): LayerStack {
        require(layer.name.isNotBlank()) { "ชื่อเลเยอร์ห้ามว่าง" }
        require(layers.none { it.id == layer.id }) { "มีเลเยอร์ id นี้แล้ว: ${layer.id}" }
        return copy(layers = layers + layer, selectedId = layer.id)
    }

    fun select(id: String): LayerStack {
        require(layers.any { it.id == id }) { "ไม่พบเลเยอร์: $id" }
        return copy(selectedId = id)
    }

    fun rename(id: String, name: String): LayerStack {
        require(name.isNotBlank()) { "ชื่อเลเยอร์ห้ามว่าง" }
        val layer = byId(id)
        require(!layer.locked) { "เลเยอร์ \"${layer.name}\" ถูกล็อก — ปลดล็อกก่อนเปลี่ยนชื่อ" }
        return copy(layers = layers.map { if (it.id == id) it.copy(name = name.trim()) else it })
    }

    /** Copies [id] directly above itself; the caller copies the file first. */
    fun duplicate(id: String, newId: String, newPath: String): LayerStack {
        val at = layers.indexOfFirst { it.id == id }
        require(at >= 0) { "ไม่พบเลเยอร์: $id" }
        val layer = layers[at]
        require(!layer.locked) { "เลเยอร์ \"${layer.name}\" ถูกล็อก — ปลดล็อกก่อนทำสำเนา" }
        require(layers.none { it.id == newId }) { "มีเลเยอร์ id นี้แล้ว: $newId" }
        val copy = layer.copy(id = newId, name = layer.name + " สำเนา", path = newPath, locked = false)
        val next = layers.toMutableList()
        next.add(at + 1, copy)
        return copy(layers = next, selectedId = newId)
    }

    fun remove(id: String): LayerStack {
        val layer = byId(id)
        require(!layer.locked) { "เลเยอร์ \"${layer.name}\" ถูกล็อก — ปลดล็อกก่อนลบ" }
        val next = layers.filterNot { it.id == id }
        val nextSelected = if (selectedId == id) next.lastOrNull()?.id else selectedId
        return copy(layers = next, selectedId = nextSelected)
    }

    fun toggleVisible(id: String): LayerStack {
        byId(id)
        return copy(layers = layers.map { if (it.id == id) it.copy(visible = !it.visible) else it })
    }

    fun toggleLock(id: String): LayerStack {
        byId(id)
        return copy(layers = layers.map { if (it.id == id) it.copy(locked = !it.locked) else it })
    }

    /**
     * Merges [id] down into the layer below it; the caller flattens the two
     * files first and passes the result via [mergedPath]. Keeps the lower
     * layer's name and position.
     */
    fun mergeDown(id: String, mergedId: String, mergedPath: String): LayerStack {
        val at = layers.indexOfFirst { it.id == id }
        require(at >= 0) { "ไม่พบเลเยอร์: $id" }
        require(at > 0) { "เลเยอร์ล่างสุดไม่มีอะไรให้รวมด้วย" }
        val top = layers[at]
        val bottom = layers[at - 1]
        require(!top.locked && !bottom.locked) { "เลเยอร์ถูกล็อก — ปลดล็อกทั้งคู่ก่อนรวม" }
        require(layers.none { it.id == mergedId }) { "มีเลเยอร์ id นี้แล้ว: $mergedId" }
        val merged = bottom.copy(id = mergedId, path = mergedPath)
        val next = layers.toMutableList()
        next[at - 1] = merged
        next.removeAt(at)
        return copy(layers = next, selectedId = mergedId)
    }

    /** Points [id] at a new file (e.g. after running a tool on it). */
    fun updatePath(id: String, newPath: String): LayerStack {
        val layer = byId(id)
        require(!layer.locked) { "เลเยอร์ \"${layer.name}\" ถูกล็อก — ปลดล็อกก่อนอัปเดต" }
        return copy(layers = layers.map { if (it.id == id) it.copy(path = newPath) else it })
    }

    /** Visible layers bottom → top, ready for [ImageOps.flatten]. */
    fun visibleLayers(): List<ImageLayer> = layers.filter { it.visible }

    private fun byId(id: String): ImageLayer =
        layers.find { it.id == id } ?: throw IllegalArgumentException("ไม่พบเลเยอร์: $id")
}
