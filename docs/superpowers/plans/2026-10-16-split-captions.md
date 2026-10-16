# Split Captions Implementation Plan

> **For agentic workers:** Use the assigned task brief and the approved spec. Work only in owned files; keep shared interfaces as named below.

**Goal:** Add dedicated split-screen captions with clear placement/read-style choices and mode-specific setup.

**Architecture:** MainActivity hosts a normal resizable caption route; the existing service owns capture and inference. Both surfaces consume existing caption presentation and reading-anchor contracts. Persist placement independently and pin it for each session.

**Tech Stack:** Kotlin, existing Jetpack Compose/Android Views, DataStore, Android multi-window. No new runtime dependencies.

**Spec:** docs/superpowers/specs/2026-10-16-split-captions-design.md

## Global Constraints

- Missing placement preference remains POPUP; enum CAPTIONS/CHAT stays unchanged; bounds stay 2/40 segments.
- No second engine or automatic capture start from navigation. No overlay requirement/window for SPLIT_SCREEN.
- Existing grayscale theme, 48dp targets, Korean labels; no decorative animation/shadow.
- Preserve source/translation pairing, bounded memory, reading anchors, privacy opt-in and saved settings.

## Review Focus

- Permission grant arrives after cancel, rotation or placement change: never launch stale work.
- Scrolled long message survives width/font/original changes and head eviction.
- Service completes or fails while Activity is absent: no orphan popup or stale running state.
- Short/narrow split pane and large text: controls reachable, caption body usable.
- Adjacent launch fails or opens full-screen: honest manual fallback and preserved session.

## Task 1: Placement and service lifecycle

Files: settings/CaptionPlacement.kt, settings/CaptionSettings.kt, data/SettingsDataStore.kt, ui/MainUiState.kt, ui/MainSetupState.kt, ui/MainViewModel.kt, service/CaptionForegroundService.kt, service/CaptionRuntimeStore.kt; corresponding unit tests.

Interfaces: CaptionPlacement { POPUP, SPLIT_SCREEN }, CaptionSettings.captionPlacement, MainViewModel.updateCaptionPlacement(placement), MainSetupState.overlayPermissionRequired and totalSteps. MainActivity owns startup permission check, route and system app picker. Service pins session placement at start and keeps settings observation independent from overlay rendering. completedCaptionSession accepts retained caption data only for split sessions.

- [x] Write behavioral tests for placement default/fallback, split start with overlay denied, dynamic setup totals, retained final split captions and new-session reset. Initial RED execution was blocked by missing build dependencies.
- [x] Implement persistence, guarded selection, mode-specific requirements, conditional popup creation and retained completion.
- [x] Run tests and record GREEN; review service diff before integration.

## Task 2: Caption pane and stable reading

Files: new ui/CaptionScreen.kt, ui/CaptionReadingView.kt (Android View host allowed), ui/CaptionReadingState.kt if needed; meaningful reading tests. Reuse overlay/OverlayCaptionPresentation.kt, OverlayScrollPosition.kt, buildOverlayUiState.

Interfaces: CaptionScreen(ui: MainUiState, onPauseResume: () -> Unit, onStop: () -> Unit, onSettings: () -> Unit, onChooseApp: () -> Unit, onSplitHelp: () -> Unit, onShowOriginalChange: (Boolean) -> Unit, onTextSizeChange: (Float) -> Unit, onDisplayModeChange: (CaptionDisplayMode) -> Unit, onStart: () -> Unit, onAcceptLanguage: (String) -> Unit, onDismissLanguage: () -> Unit, modifier: Modifier = Modifier). The screen is usable inactive, starting, active, stopping, completed or error. Original/font/read style use existing settings and More dialog; status is compact but details reachable.

- [x] Add regression for reader restoration and observe RED-to-GREEN for session-generation binding.
- [x] Implement pane with line anchors, save/restore reader position across recreation, bounded 2/40 messages, explicit latest action and no motion animation.
- [x] Verify targeted tests; compile and statically review narrow-pane layout. Device layout remains unverified.

## Task 3: Main route, app selection and setup UI

Files: MainActivity.kt, ui/MainScreen.kt, new ui/SplitScreenLauncher.kt or policy helper, AndroidManifest.xml, overlay/OverlayController.kt labels and relevant l10n strings.

Consumes task1 placement/setup and task2 CaptionScreen. MainActivity route saved through recreation, main setup and captions reachable without restarting capture. Return to caption action available for split; leaving caption route does not stop service. Explicit Stop does. Projection approval stays in MainActivity. Split launcher queries MAIN/LAUNCHER apps, excludes self, launches explicit component with NEW_TASK|LAUNCH_ADJACENT, catches failure and retains manual help. No forced ratio.

- [x] Add tests for split launch policy and mode-specific start gate. Initial RED execution was blocked by build infrastructure; final regression suite passes.
- [x] Add visible placement controls above checklist, dynamic 2/3 counts, shared read-style labels, popup-only geometry controls, caption-route entry and manual fallback.
- [x] Add manifest resize and launcher query scope; preserve source/model/privacy behavior.
- [x] Run tests and compile.

## Task 4: Integration and delivery

Files: app/build.gradle.kts version, README feature instructions, this plan checkboxes. Retain normal app signing/config; locally built preview may require explicit signing recovery.

- [x] Run Python privacy/regression suite; Gradle testDebugUnitTest lintDebug assembleDebug.
- [x] Independent full-diff review; fix and re-review the system-inset finding.
- [x] Inspect manifest, assets, signatures and APK; no emulator/device execution was available.
- [ ] Publish verified branch/PR, check CI, merge under prior authorization and deliver persisted APK matching reviewed source.

## Verification record

- Untouched baseline: 359 Android tests and 51 Python tests passed.
- Final preview: 381 Android tests in 49 suites passed; lint has 0 errors and 25 warnings. The Python suite has 51 passing tests and the privacy audit passed.
- Independent review has no remaining findings after the system-inset fix. Status-bar, narrow-pane and device-specific split behavior were reviewed in source, not exercised on Android hardware.
- Preview APK: version 1.2.0 / code 11, package `com.hebi.livetranslate.preview`, same signing certificate as the existing preview. ZIP and all seven native libraries meet 16 KiB alignment; pinned support assets and the external large-model boundary were verified.
- Preview APK SHA-256: `633dc6ad7f028f03fafa1d3522ea6ba6c2c2f2f0de1a1364e7c4dd1436a1c4f2`.
