# Almus Studio

An offline-only Android multitrack audio production app. No account, no
cloud, no network permission requested anywhere in the app — everything
(recording, playback, mixing, project storage) runs entirely on-device.

This repository currently implements **Phase 1** of the roadmap in
[`ROADMAP.md`](ROADMAP.md): a working DAW foundation with real multitrack
playback, WAV recording, a project format, and a CI-built debug APK. It is a
functional starting point, not a finished professional DAW — see
[Known limitations](#known-limitations-be-technically-honest) below before
you rely on it for real work.

## Why these technologies

| Concern | Choice | Why |
|---|---|---|
| App language | Kotlin + Jetpack Compose | Modern, less boilerplate than XML/Views for a UI this state-heavy (playhead, meters, per-track controls all changing constantly); first-class coroutines for the UI polling loop. |
| Real-time audio | C++ (NDK) + [Oboe](https://github.com/google/oboe) | Kotlin/JVM audio APIs (AudioTrack/AudioRecord via Java) add GC pauses and scheduling jitter that are audible as glitches in multitrack playback. Oboe wraps AAudio (API 27+) / OpenSL ES (API 26) and picks the best one automatically, giving the lowest latency Android currently offers without hand-rolling both backends. |
| Cross-thread audio control | A lock-free SPSC command queue (`command_queue.h`) | The Oboe callback runs on a real-time thread — it must never block on a mutex, allocate, or do I/O, or the OS can starve it and you hear a glitch. Every UI/JNI call *enqueues* a change instead of mutating engine state directly. |
| Project storage | Local JSON (Moshi) under `getExternalFilesDir()` | No database is warranted for the current schema (a handful of tracks/clips per project); JSON is trivially inspectable/debuggable, and `getExternalFilesDir()` needs no runtime permission on API 26+ while still being visible to a file manager or connected computer — unlike internal-only storage. |
| Audio file format | WAV (PCM/float) | Simple to decode, encode, and reason about without a codec license question; MP3/AAC encoding is left for a later export-quality phase (see roadmap). |

## Architecture

```
Kotlin/Compose UI  →  StudioViewModel  →  AudioEngine (JNI, Kotlin object)
                                              │  enqueue-only calls
                                              ▼
                                    CommandQueue (lock-free SPSC ring buffer)
                                              │  drained at top of each callback
                                              ▼
                              AlmusAudioEngine (C++, owns Oboe streams)
                               ├─ Output stream callback → mixes tracks/clips
                               └─ Input stream callback  → writes WavWriter
```

- **UI thread**: Compose screens + `StudioViewModel`. Polls playhead/meters
  every 50ms (`StateFlow`s) rather than the audio thread pushing to it, since
  the audio thread must never call back into the JVM per-buffer.
- **JNI bridge** (`jni_bridge.cpp`): translates each `AudioEngine.kt` external
  function into either a synchronous read (e.g. `getPlayheadFrame`) or an
  enqueued `Command`.
- **Real-time audio thread**: owned entirely by Oboe. `drainCommands()` runs
  first in every callback (cheap, bounded, lock-free), then `mixInto()` sums
  every unmuted track's active clips into the output buffer.
- **Structural changes** (add/remove track or clip) briefly take a
  `std::mutex` — *not* on the per-sample hot path, only around the handful of
  vector insert/erase calls — so the offline exporter can safely read
  `tracks_` from its own thread. This is documented in `audio_engine.h`.

## Known limitations (be technically honest)

Per the project's own requirement to never claim more than is implemented:

- **No resampling.** Imported/recorded WAV files play back correctly only at
  the project's sample rate (48kHz by default). A file recorded at 44.1kHz on
  a 48kHz project will play slightly pitched/timed wrong until a resampler
  stage is added (planned for Phase 2/3).
- **Offline export renders a fixed cap**, not yet the true end-of-arrangement
  frame count computed from the project model — wire `totalFrames` in
  `StudioViewModel` from the furthest clip's end before relying on export for
  a real song.
- **No waveform editing yet** (trim/split/crossfade) — clips play back
  start-to-end at their recorded/imported length. This is explicitly a Phase
  2 item in `ROADMAP.md`, not a Phase 1 claim.
- **No effects (EQ/compressor/reverb/etc.) are implemented yet.** The data
  model (`EffectSettings`) exists so projects can eventually store them, but
  the mixer does not yet process any effect — Phase 2/3 item.
- **No auto-tune / pitch correction yet.** Also a data-model placeholder only.
- **Recording is mono input, mixed to a single monitor track**; the DAW does
  not yet support multi-channel audio interfaces explicitly (Oboe will use
  whatever the OS reports as the default input).
- **Undo/redo is not implemented.** Track/clip edits are saved immediately.

None of the above are silently stubbed with fake UI — the corresponding
controls either aren't in the Phase 1 UI yet, or (for BPM/effects data
fields) exist only as inert data the mixer doesn't yet read.

## Repository layout

```
app/
  src/main/java/com/almus/studio/
    audio/        Kotlin JNI wrapper, waveform analyzer, time-conversion helpers
    data/         Project/Track/AudioClip models + local JSON repository
    ui/           Compose screens, theme, reusable components
    viewmodel/    StudioViewModel (single source of UI truth)
  src/main/cpp/    Native Oboe-based multitrack engine (see architecture above)
  src/test/        JVM unit tests (no device/emulator needed)
.github/workflows/ CI: build + test + APK artifact upload
```

## Building the APK

### Locally

You need Android Studio (Koala or newer) with the NDK and CMake components
installed (Settings → Languages & Frameworks → Android SDK → SDK Tools →
check "NDK (Side by side)" and "CMake"), or a system `gradle` install (8.7+).

```bash
# One-time, if you don't already have gradlew: generates the wrapper jar/scripts.
gradle wrapper --gradle-version 8.7

./gradlew assembleDebug
```

The debug APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

### Via GitHub Actions

Push to `main` or open a pull request; `.github/workflows/android-build.yml`
will:

1. Check out the repo.
2. Install JDK 17 and the Android SDK.
3. Provision Gradle 8.7 (no committed wrapper jar required).
4. Run `gradle test`.
5. Run `gradle assembleDebug` and upload the APK as a workflow artifact
   named `almus-studio-debug-apk`.
6. Optionally build and upload a signed release APK **only if** the repo has
   `ALMUS_RELEASE_KEYSTORE_BASE64`, `ALMUS_RELEASE_KEYSTORE_PASSWORD`,
   `ALMUS_RELEASE_KEY_ALIAS`, and `ALMUS_RELEASE_KEY_PASSWORD` configured as
   repository secrets. No signing key is ever committed to the repository.

Download the APK from the workflow run's "Artifacts" section.

## Installing and testing on an Android phone

1. On your phone: Settings → About phone → tap "Build number" 7 times to
   enable Developer Options, then Settings → Developer options → enable
   "USB debugging".
2. Connect the phone via USB and accept the "Allow USB debugging?" prompt.
3. From a computer with the APK downloaded:
   ```bash
   adb install app-debug.apk
   ```
   or copy the APK to the phone and open it directly (you'll need to allow
   "install unknown apps" for whichever app you copied it with).
4. Launch **Almus Studio**, tap **New Project**, then tap the record button —
   you'll be prompted for microphone permission the first time.
5. For a quick multitrack test: create two tracks, import a WAV file (or
   record one) into each via the import icon on a track's timeline lane, then
   hit play. You should hear both mixed together, and can adjust each
   track's volume/pan slider live during playback.

A physical device is strongly recommended over an emulator for anything
audio-related — emulator audio timing does not reflect real-world latency,
which matters a great deal for a DAW.

## Running tests

```bash
./gradlew test          # JVM unit tests (project model, time conversion)
./gradlew connectedCheck  # instrumented tests, requires a connected device/emulator
```

## Roadmap

See [`ROADMAP.md`](ROADMAP.md) for Phases 2–4 (editing/effects, beat maker &
MIDI & offline vocal pitch correction, and professional refinement).
# Almus-Studio-
