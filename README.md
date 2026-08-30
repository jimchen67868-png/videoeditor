# Video Editor (CapCut-style) — Kotlin Android scaffold

A working starting point for a CapCut-like editor, built on **Media3 Transformer**
(Google's official media editing/export library) rather than raw MediaCodec or a
heavier FFmpeg dependency. This is a scaffold, not a finished app — see "What's
missing" below before you show this to users.

## Structure

```
app/src/main/java/com/example/videoeditor/
├── model/
│   └── Timeline.kt          # Core data model: Project, Clip, TextOverlay, AudioTrack
├── effects/
│   ├── FilterShaderEffect.kt   # Color filters as Media3 RgbMatrix (GL shader) effects
│   └── TextOverlayEffect.kt    # Burns in text overlays via Media3 OverlayEffect
├── export/
│   ├── TimelineExporter.kt     # Project -> Media3 Composition -> rendered MP4
│   └── ExportWorkerService.kt  # Foreground service so export survives backgrounding
└── ui/
    ├── EditorViewModel.kt   # Single source of truth for the in-progress Project
    ├── EditorActivity.kt    # Preview player + timeline + filter buttons + export
    └── TimelineView.kt      # Custom View: clip strip with drag-to-trim handles
```

## Why Media3 Transformer

- It's actively maintained by Google and is the current recommended tool for
  programmatic video editing/export on Android (trim, concatenate, speed change,
  re-encode).
- The same `Effect` objects (filters, text overlays) can, with more wiring, be
  used both for live preview (via ExoPlayer's `setVideoEffects`) and for the
  final export — one implementation instead of two.
- It avoids bundling FFmpeg's native binaries, which are a large APK size cost
  and, as of the `ffmpeg-kit` project's archival in 2023, no longer have a single
  clearly-maintained Kotlin wrapper. If you need audio processing beyond what
  Media3 offers, evaluate current forks before depending on one.

## Setup

1. Open in Android Studio (Koala+ recommended), let Gradle sync.
2. Requires `minSdk 24`, `compileSdk 34`, JDK 17.
3. Run on a device/emulator with a real video file to test import — the emulator's
   default gallery is often empty, so push a sample MP4 via `adb push`.

## What's implemented

- Data model for a multi-clip timeline with trim points, speed, filters, text
  overlays, transitions (data-level), and one background audio track.
- Trim + concatenate + re-encode export pipeline via Media3 Transformer.
- Color filters (grayscale, sepia, vivid, cool, warm) as GPU shader passes.
- Text overlay burn-in with time-windowed visibility.
- A basic draggable timeline UI for selecting clips and adjusting trim points.
- A foreground service stub for background export with progress notification.

## What's missing / known gaps (read before building on this)

- **Live preview applies filters and approximates speed** — `EditorActivity`
  now calls `ExoPlayer.setVideoEffects(...)` using the same `FilterShaderEffect`
  the exporter uses, swapping the active filter as playback crosses between
  clips. Speed is approximated via the player's *global* playback speed (ExoPlayer
  doesn't support true per-MediaItem speed), so it's close but not frame-accurate
  during preview -- export remains the source of truth for exact timing.
  **Text overlays still don't render live** (only at export) since wiring
  `OverlayEffect` into the live pipeline needs the same current-clip-lookup
  plumbing but wasn't done yet -- straightforward to add following the filter
  pattern in `applyLiveEffectsForCurrentItem()`.
- **Transitions are stored in the data model but not yet applied at export.**
  Media3 Transformer 1.4.x supports transitions via `EditedMediaItemSequence`
  gap/overlap configuration and custom `VideoCompositorSettings`; this scaffold
  stops short of wiring crossfades so you can decide the exact transition
  library/approach.
- **No thumbnail generation** in `TimelineView` — clips render as flat colored
  blocks. Real thumbnail strips need `MediaMetadataRetriever.getFrameAtTime`
  calls cached to bitmaps, ideally off the main thread.
- **No persistence** — closing the app loses the project. Add a Room entity
  mirroring `Project`/`Clip` and save on each edit or periodically.
- **`ExportRequestHolder` is a static in-memory handoff**, fine for a scaffold,
  not fine for production — pass a project ID and look it up from a repository
  instead, since the service can be killed and restarted by the system.
- **No undo/redo, no music track picker UI, no audio ducking implementation**
  (the `duckingEnabled` flag exists in the model but nothing reads it yet).
- **Permissions**: manifest requests `READ_MEDIA_VIDEO`/`READ_MEDIA_AUDIO` but
  the Activity doesn't request them at runtime — add an `ActivityResultContracts.RequestPermission`
  flow before the video picker launches on API 33+.

## Suggested next steps, in order

1. Wire live preview effects (`ExoPlayer.setVideoEffects`) so what you see
   while editing matches the export.
2. Implement crossfade transitions in `TimelineExporter`.
3. Add thumbnail generation to `TimelineView`.
4. Add Room persistence for `Project`.
5. Runtime permissions + a proper music/audio track picker.
