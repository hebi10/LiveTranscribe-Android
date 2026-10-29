# Real-time Caption Stability Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development for independent fixes and integration review.

**Goal:** Deliver the approved review improvements without changing the offline model set or sending speech outside the device.

**Architecture:** Incremental cumulative recognition feeds a stable sentence committer; final audio flushes the remaining suffix. Runtime lifecycle owns bounded diagnostics and language-ID release, while translation errors retain typed causes.

**Tech Stack:** Kotlin, coroutines, Android Views/Compose, C++ JNI, Gradle, Python.

**Spec:** `docs/superpowers/specs/2026-10-29-realtime-caption-stability-design.md`

## Global Constraints
- Preserve existing offline models, package identity for signed releases, history consent and backup exclusions.
- No new dependencies, no transcript/audio diagnostics, no signing key generation, no push or publication.

## Review Focus
- Revisions of already committed text must not duplicate captions.
- Decoding slower than capture must keep bounded pending work.
- Stop must not free native models while calls are active.
- Density changes must preserve preferred dp size and reader position.
- Missing release secrets or wrong certificate must never fall back to an installable differently signed APK.

## Tasks
- [x] Add incremental PCM snapshots and stable sentence commitment with unit regression tests.
- [x] Integrate independent language identification, release its model after joined work, and report processing lag.
- [x] Align translation drain with per-request deadlines and show delayed ASR shutdown without unsafe cancellation.
- [x] Preserve typed native translation timeout and cancellation; test repository error propagation.
- [x] Recompute popup geometry and control dimensions on density changes; test geometry conversions.
- [x] Protect release signing and clearly separate debug artifacts; test signing helper failure paths.
- [x] Update current privacy/license/readme documents; run available suites and independent integration review.
- [x] Commit the final changes and create a reviewable patch without pushing or publishing.

## Verification record (2026-10-29)
- Exact upstream base tree and signed commit were verified against GitHub: `66b3c64fc407b44380af3dd832dee31ddbcc5c9f`.
- Python regression suite: 66 tests passed; privacy source audit passed.
- Both production native source files passed GCC 13 C++17 syntax validation with `-Wall -Wextra -Wpedantic -Werror` against official pinned llama.cpp headers and official OpenJDK JNI headers. This does not run native inference or Android linking.
- Independent speech review caught lexical-prefix and quote preservation bugs; fixes and regression cases were added. Integration review caught startup failure state and destruction-time loss reporting; these were corrected.
- Kotlin/Android regression tests, lint and APK build were attempted but could not start: the connected text-only source transfer cannot retrieve the existing upstream binary wrapper JAR, and no local Kotlin compiler/Gradle distribution/Android SDK is installed. The upstream repository does contain its wrapper JAR; this is a local verification limitation.
- Existing native ASR has no cancellation API. Stop reports prolonged waiting while retaining resources until decode returns; a hard native execution deadline is not implemented.
- Original release keystore/certificate and protected GitHub environment are not provisioned here; no signing keys were created, no signed APK/update installation was validated, and no remote push/publication was performed.
- The incremental recognition strategy adds repeated native inference. Its adaptive intervals bound added work, but handset latency/accuracy/temperature/battery improvements require real-device measurements.

## Final review
- Independent integration reviewer rechecked both startup and destruction fixes and found no further high-confidence blocker by static inspection.
- Updated helper regressions, incremental sentence/lexical-boundary/quote regressions, adaptive PCM snapshots, and asynchronous language-ID ordering tests are present but Kotlin execution is pending the Android toolchain.
- Final available checks: Python 66/66, privacy audit, native C++ syntax check, and diff whitespace check all passed. Full Gradle test/lint/APK command still stopped before execution at missing local GradleWrapperMain.
