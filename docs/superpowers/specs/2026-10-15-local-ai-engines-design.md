# Local AI engines 1.1.0

## Approved outcome
Replace the existing English Vosk/Japanese ReazonSpeech recognition and ML Kit translation with the recommended locally executed models, keeping speech and transcript text on the phone. The user delegated model selection and requested implementation of the preceding recommendation. Existing start, overlay, history, language recommendation, permission and consent behavior must remain usable.

## Chosen configuration
- Qwen3-ASR 0.6B int8 through the already pinned sherpa-onnx 1.13.8 Android AAR; English and Japanese remain the exposed source languages. Independent Whisper Tiny language recommendation remains local. Silero VAD guards silence/music and bounded speech segments.
- Tencent Hy-MT2 1.8B Q4_K_M through pinned llama.cpp CPU JNI. Korean target, English/Japanese source, no cloud fallback. Two CPU threads per engine, bounded input/output/context and native cancellation/deadline. Actual reference-audio and text smoke checks are required; no claim of measured phone latency or universal accuracy superiority.
- Models total approximately 2.12 GB and are downloaded only after an explicit model-preparation action. Wi-Fi and mobile data are both permitted. Downloads are fixed HTTPS GETs, contain no speech, transcript, identifiers or telemetry payloads, and use pinned revisions, byte lengths and SHA-256 checksums.

## Model lifecycle
Model files live in noBackupFilesDir/ai-models. Initialization/refresh reads local files only. Download .part files can resume with validated HTTP Range responses; unexpected ranges, oversized payloads and checksum failures never publish a ready model. Files become usable only after verification and atomic rename. Setup displays total progress, cancellation, retry/resume and storage/network errors. Interrupted downloads can be resumed by another explicit action. Both source languages share one model bundle.

## Runtime ownership
The existing bounded capture/recognition/translation queues, skipped-work notices, pause boundaries and final drain remain intact. Native recognition resources are owned until the capture/decoding worker exits. Translation serializes native operations, aborts on cancellation/deadline, rejects incomplete generation, and unloads after session termination without freeing an active context. Repeated starts must remain safe.

## Integration and distribution
Remove the ML Kit and Vosk runtime dependencies and unused production clients. Keep existing settings and saved records; no migration that deletes user content. Model names and first-run copy describe the new setup truthfully. No automatic capture or model download. Package shared VAD and language-ID assets, native libraries and licenses in an arm64-v8a APK; large inference models remain user-initiated downloads. Version 1.1.0/code 9, canonical app ID unchanged, preview ID/signing continuity preserved. Build with NDK 28.2.13676358/CMake 3.22.1 and validate 16KiB alignment.

## Validation
Model transport integrity/resume/cancellation tests, setup permission/readiness regressions, bounded native lifecycle tests, actual CPU inference smoke checks, full Android/Python suites and lint, independent runtime/UI/privacy/native review, CI and merged-tree equality. Physical-device capture accuracy, thermal behavior and battery consumption remain unverified unless a device becomes available.
