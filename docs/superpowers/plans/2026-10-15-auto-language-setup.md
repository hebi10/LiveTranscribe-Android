# Automatic recognition and first-run setup

## Authorized scope

Implement the approved automatic / Japanese / English recognition modes and make required first-run preparation understandable. Preserve saved language choices, downloaded models, history consent and signing identity. New installations default to automatic recognition. Version 1.1.1/code 10.

## Recognition and translation

- Use the existing Qwen recognizer for all modes. Set the native stream language to English or Japanese for manual selections; automatic streams have no forced language.
- Identify the language of the exact bounded speech segment used for each automatic transcript with the already bundled Whisper language identifier. Never reuse the preceding segment's language when detection fails.
- Normalize detected languages against the Qwen/HY-MT common language catalog. Use `und` for unknown/unmapped detections, never a guessed English label. Keep the current source with its queued translation and optional history entry. Automatic results, including detected Korean, use the Korean translation prompt: a Korean label must not bypass translation of foreign words in a mixed segment. Explicit non-automatic Korean identity calls retain their existing behavior.
- Run the existing change-language recommendation only in manual mode. Automatic mode requires no restart between speech segments and no additional model download.
- Keep the existing bounded queues, finalized-caption stability, shutdown ownership, privacy and on-device inference.

## Setup presentation

- A single checklist contains audio permission, permission to display over other apps, and the shared AI models. Show completion count, remaining count and the next available action.
- Completed rows remain visible while preparation is incomplete. Once all three are complete, show a compact ready summary with expandable details and a clear Start action.
- Model checking, downloading, interruption, retry and completion are distinct states. Show meaningful progress and keep downloads explicit; Wi-Fi and mobile data remain supported.
- Explain Android screen-sharing confirmation separately because system-audio sessions request it at each start. Notification permission and optional history storage do not block the three-step setup checklist.
- Use the existing Material theme, accessible touch targets and layouts that accommodate narrow screens and larger text.

## Work and verification

1. Add a shared language policy, default/migration behavior and translation validation.
2. Connect native forced-language options and same-segment automatic identification with regression coverage.
3. Snapshot language metadata through the service, bounded translation queue and history.
4. Implement the checklist and mode controls with state tests for permission denial, model checking/error/resume, readiness and active sessions.
5. Run the full Android unit suite, lint, Python preparation/privacy checks and independent code reviews. Build both canonical and separately signed preview variants, inspect manifests, signatures, assets and 16KiB native alignment.
6. Publish the reviewed tree, require exact-head CI success, merge and deliver the preview APK. Actual Android touch behavior, recognition speed, thermals and battery require a physical device and must not be claimed as tested here.

## Verification record

- Full Android unit suite: 359 tests, zero failures/errors/skips. `testDebugUnitTest lintDebug assembleDebug` succeeded; lint has zero errors and 22 existing warnings.
- Python preparation suite: 51 tests passed. Pinned support assets/native source verified; static privacy audit passed.
- Independent UI and native/service reviewers found no remaining blockers. Review caught and resolved the automatic-Korean identity bypass for mixed-language text; fresh-install DataStore reads and unrelated writes consistently preserve automatic mode.
- Existing native Qwen model ran four bounded public-sample checks: Japanese and English each with matching forced option and automatic mode. Fresh automatic streams had no forced-language option; all results were nonempty. This verifies option wiring, not quality superiority. The host was Linux CPU, not Android.
- Canonical 1.1.1/code10 APK passed manifest/signature, arm64 assets, license, ZIP CRC and 16KiB ZIP/ELF alignment checks. Preview packaging and exact-head remote CI are the remaining delivery gates.
