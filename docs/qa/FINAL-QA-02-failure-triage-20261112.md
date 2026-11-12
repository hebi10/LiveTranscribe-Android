# FINAL-QA-02 — 1차 통합 QA 실패 분석·수정 기록

- Date: 2026-11-12 (KST)
- PR: https://github.com/hebi10/LiveTranscribe-Android/pull/68
- Original exact head: `7cdef7c385c5057cb111756baadc939459375e47`
- Status: **Original QA failed. New candidate committed; new CI/visual QA must be checked before merging.**

## 1. 원본 실행 증거

| Workflow | Run | Result | Cause |
|---|---|---|---|
| Private Android APK | [#43152664584](https://github.com/hebi10/LiveTranscribe-Android/actions/runs/43152664584) | failure | 468 JVM tests, 5 failures |
| UI-GAP-07 API 29/35/36 | [#43152664510](https://github.com/hebi10/LiveTranscribe-Android/actions/runs/43152664510) | failure | `CaptionToolsUiTest.kt` AndroidTest Kotlin compilation |
| Play current UI captures | [#43152664483](https://github.com/hebi10/LiveTranscribe-Android/actions/runs/43152664483) | failure | same AndroidTest compilation |
| Responsive UI QA | [#43152664794](https://github.com/hebi10/LiveTranscribe-Android/actions/runs/43152664794) | failure | same AndroidTest compilation |

## 2. 분석과 적용 수정

### 2-1. JVM — CaptionToolsPersistenceTest (2 failed)

- `TemporaryFolder.newFile()` had created zero-byte `glossary.json` and `profiles.json` files. The repositories now deliberately reject malformed existing JSON rather than erasing it.
- Fix only the **test fixture**: start with a non-existent `File(tmp.root, ...)`; preserve the production repository safety rule.
- Preserve separate malformed-file cases that assert the original bytes are not overwritten.

### 2-2. JVM — SetupPresentationTest (3 failed)

- `UX-01` changed model progress UI copy to distinguish downloading from transferred-but-not-verified.
- Align unit assertions:
  - partial downloading: `모델 다운로드 중`
  - transferred 100%, not READY: `전송 완료 · 모델 파일 확인 중`
  - verification guidance must contain `검증` instead of `이어받`
- No change to actual model download, integrity verification, or READY logic.

### 2-3. Instrumentation compile — CaptionToolsUiTest

- `onNode`, `onAllNodes` were incorrectly imported as top-level symbols; these are available as ComposeTestRule methods.
- `SemanticsNodeInteractionCollection.onLast()` does not exist. Select the final matching node by `collection[collection.fetchSemanticsNodes().lastIndex]`.
- Fix only test code. Keep the test's local test-only files and the actual UI implementation.

## 3. Release gate

- [ ] Verify **all 4 workflows** on the same new exact PR HEAD.
- [ ] Run the full Kotlin test suite; no failing test.
- [ ] API29/35/36 connected Android captures pass and actual 70 PNG provenance manifest validates.
- [ ] Popup normal/expanded/minimized and synthetic history screenshots pass.
- [ ] Validate user glossary + caption presets on API35, including CRUD and editing restrictions.
- [ ] Review screenshots for clip/overflow with 160% font, dark/light, tablet and simulated fold sizes.
- [ ] If all pass, only then review safe squash merge into main. Separate authorization/QA for Google Play release.
- [ ] Do not claim production ASR/translation, physical fold, or real Play tester install success from synthetic UI tests.

**No final approval granted by this document.** A failed or still-running check means the PR remains unmerged.
