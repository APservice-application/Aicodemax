# UI Reference — Audio Editor

## Reference applications
WaveEditor, Lexis Audio Editor, BandLab (layout concepts only).

## Observed
- Layout: top bar · waveform dominant · transport · tools.
- Navigation: file list → waveform editor → effects.
- Controls: cut/copy/split/trim, fade in/out, gain, normalize, noise
  reduction, markers.
- Interaction: select region → action; playhead follows; undo chain.
- Gestures: pinch zoom waveform, drag select.
- Responsive: transport condenses on narrow screens.

## Adopt
Waveform-first editor, region selection → tool actions, transport bar,
effect list with parameters.

## Change
Ops call audio.* tools (trim/concat/fade/gain/normalize/mix/autocut/
beats); record/podcast/voice integrate; render/export to Tasks.
