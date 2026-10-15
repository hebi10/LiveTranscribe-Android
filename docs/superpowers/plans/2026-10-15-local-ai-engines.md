# Local AI Engine Implementation Plan

> **For agentic workers:** Use coordinated subagent implementation with explicit file ownership and root integration. Check each task before whole-branch review.

**Goal:** Upgrade recognition and Korean translation to local Qwen3-ASR/Hy-MT2 with reliable explicit model setup.

**Architecture:** A shared verified model store gates both engines. Existing speech/session boundaries wrap Qwen; a serialized JNI backend wraps Hy-MT2. Current UI and data stores retain their structure.

**Tech Stack:** Kotlin, Compose, coroutines, sherpa-onnx 1.13.8, llama.cpp C++/JNI, Python build preparation.

**Spec:** ../specs/2026-10-15-local-ai-engines-design.md

## Global Constraints
- Voice and text stay on device; network only explicit fixed HTTPS model GETs.
- Source languages en/ja, target ko; no cloud fallback or silent download/start.
- Native ownership and bounded queues preserved; input/token/time limits enforced.
- Version1.1.0/code9; SDK36/min29; arm64; NDK28.2.13676358/CMake3.22.1.
- Existing settings/history preserved and previews retain the existing signing key.

## Review Focus
- Interrupted, corrupt or range-ignoring downloads must never unlock Start.
- Cancel/close during native creation and decode must not free live resources or leave worker backlog.
- Long speech, silence/music and output truncation must have bounded processing and honest results.
- Source changes and configuration recreation during setup must preserve progress and readiness.
- Native/model dependencies must be pinned, reproducible, free of telemetry/upload code paths, and16KiB compatible.

### Task1: Verified model store
- [x] Add model manifest and models/OfflineModelStore with explicit prepare/refresh/cancel/requireReadyFile APIs.
- [x] Fail-first temporary-file and fake-transport tests for hashes, sizes, range validation, atomic publication, cancellation, concurrency and network-free refresh; implement and pass.
- [x] Replace TranslationModelSetup internals with the shared store, preserving observable setup states.

### Task2: Speech engine
- [x] Inspect actual AAR Qwen APIs and verify official model files; run local CPU reference sample and baseline comparison.
- [x] Replace native recognizer with LocalQwenSttClient(context,modelDirectory:File); generalize existing bounded Japanese pipeline for en/ja.
- [x] Keep VAD, pause/final segment handling, skip notices and resource lifecycle; adapt regression tests and remove obsolete Vosk classes.

### Task3: Local translation
- [x] Pin llama.cpp source and official Hy-MT2 Q4_K_M model; run actual Japanese/English-to-Korean smoke examples.
- [x] Add CMake/JNI native backend and LocalTranslationRepository(modelFile:()->File), serial native ownership and cancellation/deadline.
- [x] Test prompt/input/output bounds, queued cancellation, close/reopen and failures; reject incomplete/empty outputs.

### Task4: Setup and application integration
- [x] Connect shared model store in AppContainer; update service start guards and native unload after safe drain.
- [x] Change Main setup state to common downloaded readiness, actual progress and cancel/resume; preserve explicit permission/start flow.
- [x] Update model/privacy copy and licenses, remove ML Kit/Vosk dependencies, exclude legacy bundled models.

### Task5: Verification and delivery
- [x] Reproducible VAD/native build preparation and CI asset checks; fail-first preparation/privacy regressions.
- [x] Run full Android/Python suites, lint and canonical/preview builds sequentially; independent code review.
- [ ] Validate APK package/version/signature/16KiB/model assets, publish exact tested tree, waitCI, merge with expectedhead, save/deliver APK.

## Verification recorded before delivery
- Full Android suite: 324 tests, zero failures/errors/skips. Python: 51 tests passed. Lint: zero errors, 22 warnings (including intentional arm64-only and conservative free-space checks).
- Independent runtime/native review: no blocking findings. Download/UI review found synchronous cancellation cleanup; fixed by moving cleanup to the existing I/O watcher and covered by a blocking-cleanup regression. Reviewer confirmed resolution.
- Actual host model probes: Qwen/Reazon FLEURS comparison and Hy-MT2 Japanese/English inference, cancellation/deadline/token bounds and lifecycle recovery. These are not physical-device performance tests.
