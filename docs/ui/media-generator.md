# Media Generator

Master spec (Generator application §1; existing gen engine §81).

## Inventory

- SCR-GEN-001 Main: kind tabs (Poster/Background/Stylize/TTS/
  Thumbnail/Script/AI-plan/VoiceFX/Music/SFX/Photo) + outputs.
- SCR-GEN-002 Create: kind-specific fields + [Create] + progress.
- SCR-GEN-003 Script→Video: script + [Build].
- SCR-GEN-004 AI plan: mode + topic + [Plan].
- SCR-GEN-005 Audio: voice/music/SFX builders + import-to-project.
- SCR-GEN-006 Photo: adjust/upscale/restore + before/after.
- SCR-GEN-007 Outputs: artifacts + Open/Save/Share/Open-in-app (§95–96).

Generation runs off UI thread (§88); completion returns a chat result
card when launched by AI (§65).
