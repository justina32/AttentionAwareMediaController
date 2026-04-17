
# Attention-Aware Media Controller

An Android app that pauses and resumes media playback automatically based on whether the user is looking at the screen. Runs entirely on-device — no network calls, no images leaving the phone.

Built as an exploration of real-time on-device ML inference pipelines: how to turn noisy per-frame model output into a stable, UX-friendly control signal.

## Demo

https://github.com/user-attachments/assets/b7094cea-f850-4394-bd76-4c4a93e9f2f7



The latency readout in the top-left is the time from `ImageProxy.analyze()` to the ML Kit callback — typically 20–40ms on a mid-range device.

## How it works

```
CameraX (front camera)
      │  frames (KEEP_ONLY_LATEST backpressure)
      ▼
FaceAnalyzer  ──►  ML Kit face detection (fast mode, no landmarks/contours/classification)
      │  (facePresent, latencyMs)
      ▼
PlayerViewModel  ──►  asymmetric debounce (700ms pause / 500ms resume)
      │  shouldPlay: StateFlow<Boolean>
      ▼
ExoPlayer (Media3) ──►  play() / pause()
```

Three layers, each with a single responsibility:

- **`FaceAnalyzer`** — a CameraX `ImageAnalysis.Analyzer` that runs ML Kit face detection on each frame and reports `(facePresent, latencyMs)`.
- **`PlayerViewModel`** — holds UI state as a `StateFlow<UiState>` and applies temporal smoothing (see below) to convert the raw per-frame signal into a stable `shouldPlay` decision.
- **`MainActivity`** — sets up CameraX, hosts the ExoPlayer, and collects `uiState` with `repeatOnLifecycle(STARTED)` so the pipeline is torn down when the app backgrounds.

## Design decisions worth explaining

These are the calls that took the most thought — the rest is wiring.

### 1. Asymmetric debounce: 700ms to pause, 500ms to resume

Raw face detection flips state constantly: a single head turn, a hand passing across the camera, a bad exposure frame — any of these produce a false "no face." Sending every flip straight to the player would produce flicker.

The fix is a debounce, but a *symmetric* debounce gets the UX wrong:

- **Pausing too eagerly** is annoying — the user glances away for half a second and the video stops.
- **Resuming too slowly** is annoying in the opposite direction — the user is back and looking but the video is still paused.

So the debounces are deliberately asymmetric: 700ms before we commit to a pause (be patient, the user may just have blinked), 500ms before we commit to a resume (be responsive, the user is ready).

A new debounce timer only starts when the signal *flips direction*, and any previous timer gets cancelled. This way consecutive "no face" frames don't reset the clock.

### 2. `KEEP_ONLY_LATEST` backpressure on CameraX

Face detection latency is variable (mostly 20–40ms, occasionally longer under thermal throttling or GC). If the camera produces frames faster than the analyzer can consume them, you have two bad options: queue them (stale data, growing memory) or block the camera (dropped frames, frozen preview).

The third option — `STRATEGY_KEEP_ONLY_LATEST` — drops anything we can't keep up with. For a real-time attention signal, stale frames are useless anyway: we only care about what the user is doing *right now*.

### 3. ML Kit in fast mode, everything else disabled

The only question this app asks the face detector is: *is there a face?* Not where the eyes are, not the smile probability, not the contour. Enabling those features costs CPU for information we'd throw away. Config:

```kotlin
FaceDetectorOptions.Builder()
    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
    .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
    .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
    .setMinFaceSize(0.15f)  // ignore tiny faces far from the camera
    .build()
```

### 4. `ImageProxy` closes *after* the ML Kit task, not before

Easy bug to ship: closing the `ImageProxy` right after handing it to ML Kit. ML Kit reads the underlying buffer lazily, so closing early corrupts the data mid-inference. The close has to happen in `addOnCompleteListener`.

### 5. Start optimistic

`UiState.shouldPlay` defaults to `true`. The alternative — default to `false` and wait for the first detection — means the video sits paused for 300–500ms on startup, which feels broken. Starting optimistic and letting the first "no face" debounce handle correction gives a much better first impression.

## Tech stack

- **Kotlin** + **Android** (minSdk 26)
- **CameraX** for the capture pipeline
- **ML Kit Face Detection** for on-device inference
- **Media3 / ExoPlayer** for playback
- **Coroutines + StateFlow** for reactive state and debounce timing
- **ViewBinding**

Everything runs on-device. No network permission is requested.

## Running it

1. Open in Android Studio (Iguana or newer).
2. Drop a video file at `app/src/main/res/raw/sample_video.mp4` (the app loads it via `RawResourceDataSource`).
3. Grant camera permission on first launch.
4. The video starts playing. Cover the front camera or look away for ~700ms and it pauses.

## What's next

A few directions I'd take this further:

- **Replace ML Kit with a custom TFLite model.** ML Kit is a black box — swapping in a quantized TFLite face detector would give control over the inference path (GPU delegate vs NNAPI vs CPU) and let me benchmark the full latency/accuracy/size tradeoff.
- **Richer attention signals.** Face presence is a crude proxy for attention. Gaze direction (is the user looking *at* the screen, or just sitting near it?) would be a better signal, and ML Kit's landmark mode plus a small classifier could get there.
- **Power measurement.** The interesting tradeoff in on-device ML is accuracy vs latency vs battery. I'd instrument the app with Android's `BatteryManager` / systrace to quantify the cost of continuous inference at different FPS.
