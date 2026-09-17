# Roadmap

Phase 1 (this repository's current state) is described in the main
[README.md](README.md). The phases below are not yet implemented; they're
recorded here so scope is explicit and nothing is silently claimed as done
before it is.

## Phase 2 — Editing and effects

- Waveform-accurate clip trimming and splitting (currently clips play back
  whole; the data model already supports `sourceOffsetFrames`/`lengthFrames`
  so the UI work is the main remaining piece).
- Crossfades between adjacent/overlapping clips.
- A real effects processing chain per track: gain, 3-band EQ, compressor,
  reverb, delay, high/low-pass — each as its own DSP module in
  `app/src/main/cpp/`, applied in `mixInto()` after the clip is read and
  before the pan/volume stage.
- Effect bypass and simple presets (stored via the existing `EffectSettings`
  model).
- A resampler stage so imported/recorded audio at a different sample rate
  than the project plays back correctly (see README "Known limitations").
- WAV export wired to the real project length (replacing the current fixed
  cap in `jni_bridge.cpp`), plus a cancel button in the UI.

## Phase 3 — Advanced music production

- Step-sequencer drum machine + basic sample playback engine, reusing the
  existing clip-scheduling machinery in `AlmusAudioEngine` with very short,
  looping clips.
- Piano roll / MIDI-style note grid for programming melodic patterns.
- Track volume automation (the `EffectSettings`/track model would grow an
  automation lane type; the mixer would interpolate gain per-buffer instead
  of reading a single atomic).
- Offline vocal pitch correction: monophonic pitch detection (e.g. an
  autocorrelation or YIN-based estimator) on a recorded/imported vocal clip,
  snapping to a chosen scale, with adjustable correction speed/strength and
  a natural/hard-tune mode. This ships as **offline processing of a clip
  first** per the project's own requirement — real-time monitoring with
  pitch correction is a harder, later goal, not promised here.
- MIDI file import/export for patterns.

## Phase 4 — Professional refinement

- Replace the fixed 10ms UI polling loop with an event-driven meter update
  where practical, to reduce battery use.
- A dedicated, independent export playhead (removing the current "briefly
  borrow the live playhead" approach documented in `audio_engine.cpp`) so
  exporting no longer risks a few frames of jitter on live playback.
- Project autosave/recovery after a crash or forced app kill.
- Broader device testing (matrix of sample rates, framesPerBurst values,
  Bluetooth vs. wired monitoring latency).
- Undo/redo across track and clip edits.
