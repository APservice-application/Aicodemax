# Image Editor Workspace

Master spec §46–47.

## Inventory

- SCR-IMAGE-001 Main: Back · Project · Undo Redo Export ⋮ · CANVAS
  (dominant) · tools (§46).
- SCR-IMAGE-002 Layers: bottom sheet / side panel (orientation);
  thumbnail + name + visibility + lock; tap selects; long-press =
  rename/duplicate/delete/merge (§47).
- SCR-IMAGE-003 Adjust: crop/adjust/color/filter wired to image.*
  tools (crop, adjust, resize, rotate, grayscale, restore, upscale,
  scopes, info).
- SCR-IMAGE-004 Text/Brush/Mask: overlays; honest capability states
  where engine support is partial (no fake controls).
- SCR-IMAGE-005 AI: enhance/upscale/restore/variation via real tools.
- SCR-IMAGE-006 Export: format/quality/size + [Export].

## Rules

Canvas dominant; tools categorized (Crop/Adjust/Color/Filter/Brush/
Text/Layers/Mask/AI, §46). Single-layer files show one honest layer
row. Every destructive op is undoable or confirmed.
