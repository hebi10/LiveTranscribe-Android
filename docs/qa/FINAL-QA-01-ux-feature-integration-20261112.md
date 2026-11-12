# FINAL-QA-01 — UX-01~03 · FEATURE-01 통합 검증 기준

- Date: 2026-11-12 (KST)
- Repo: `hebi10/LiveTranscribe-Android`
- Baseline: `ux/ux-03-history-settings-states-20261112@e0e3da3a9921709659a191002fb97bf8a7ce8f7a`
- Test source: `qa/final-qa-01-ux-feature-20261112` (exact PR head to be determined by GitHub)
- **Status at preparation: CI NOT YET VERIFIED. Do not merge or promote to Google Play merely because this document exists.**

## Scope

### Native, privacy and build
- GitHub `Private Android APK` PR workflow: actionlint, privacy audit, Python regression, Kotlin `testDebugUnitTest`, `lintDebug`, `assembleDebug`, local model boundary and native/ZIP 16 KB checks.
- User settings, offline STT/translation model, signing and existing Play release tracks unchanged.
- Release-signing / AAB upload steps are intentionally excluded on PR.

### Actual emulator screen matrix
- `UI-GAP-07 final responsive matrix` on API 29, 35 and 36.
- 14 variant executions: API 29 (2), API 35 (10), API 36 (2).
- 5 captured UI screens per variant: home, settings, history-empty, caption-tools-empty, split-caption-preview.
- **Expected 70 Android PNGs** (10 + 50 + 10), with source SHA, dimensions, color-mode, font-scale and nonduplicate SHA-256 verification.
- Variants include light/dark, 160% font, landscape, tablet and simulated fold display sizes. Fold size emulation does **not** validate physical folding.

### Real WindowManager popup and history UI
- `Play current UI captures`: six idle app screenshots, three real emulator popup layouts (compact, expanded, minimized), and four synthetic-history UI evidence frames.
- These captures are QA evidence, **not actual successful audio recognition** and not replacement Play Store marketing screenshots.
- `Android responsive UI QA`: compact 160%, landscape, tablet six additional source-tagged screenshots.

### Functionality
- New isolated JVM unit cases:
  - Glossary add/edit/delete with stable IDs.
  - Case handling and regex-significant user text rendered literally.
  - Profile add/edit/delete; language, font size, original-text option retained.
  - Damaged JSON cannot be treated as an empty file and overwritten.
- API 35 Compose test against test-only local file repositories:
  - Glossary create/edit/delete using real management UI.
  - Preset save, select, apply callback and controls disabled when translation runs.
  - Existing synthetic history search / delete dialog / light-dark UI capture.
- No user transcript or application account data should be read, copied or modified.

## Preflight fixes included with QA
1. Align outdated UI test assertions with UX-01 and UX-03 text, including settings expansion and empty-history labels.
2. Add the Caption Tools screen to the source-verified responsive screenshot manifest.
3. Adapt the pre-existing PlayStore and responsive capture assertions to the current home copy.
4. Add narrow scope automated functional tests, without changing STT, translation, download, signing or Play state.

## Acceptance checklist — fill after GitHub Actions and manual review

- [ ] PR build/lint/unit/privacy/16 KB native APK checks successful on exact head SHA
- [ ] API 29 actual screenshots + manifests and checks successful
- [ ] API 35 actual screenshots + manifests and checks successful
- [ ] API 36 actual screenshots + manifests and checks successful
- [ ] 70 expected QA PNGs generated with unique hashes, correct SHA and reported sizes
- [ ] Local test-only glossary and preset UI tests successful
- [ ] Existing history and overlay capture tests successful
- [ ] Manual visual comparison of screenshots for clipping, button access, 160% text and dark/light contrast
- [ ] Any failures fixed and all checks repeated on the final PR head
- [ ] Only after that: review merge into `main`; update Play internal test in a separate approved release step

## Not verified by CI
- Real `~2.1GB` model installation/repair/resume on ARM64 hardware.
- Live microphone/mediaProjection capture, English/Japanese recognition to Korean translation, latency/CPU/PSS/thermal/battery metrics.
- Physical folding, hinge behavior, real app-to-app popup drag/resize and OEM permissions.
- Google Play Alpha foreground-service declaration videos, review submission and tester installation.

`UI-GAP-08` physical-device cases remain **NOT RUN** and must not be labeled passed from emulator UI captures.
