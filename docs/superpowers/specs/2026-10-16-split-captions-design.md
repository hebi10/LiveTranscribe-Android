# 분할 화면 자막

Approved in conversation on 2026-10-16: dedicated caption space that shrinks the neighboring video app through Android system split-screen, with particular attention to readable, restrained design.

- Screen placement and reading style are independent: 화면 배치 [분할 화면 / 팝업], 읽기 방식 [최근 자막 / 자막 목록]. Preserve CAPTIONS/CHAT persisted names and 2/40 caption segment limits. Missing placement preference remains POPUP for existing users.
- Normal resizable caption route in MainActivity, sharing the existing application-owned runtime and foreground service. No second engine, no overlay permission/window for SPLIT_SCREEN sessions. Session placement is fixed until stop; resizing, focus and Activity recreation must not restart capture.
- Opaque existing grayscale theme, compact header with status, pause/resume, stop and more. Main body fills remaining pane; no decorative bubbles, avatars, animation or additional dependencies. Use 48dp touch targets and accessible Korean labels.
- Reuse committed source/translation pairing, stable IDs and existing line-based reading-anchor helpers. New long messages start at their first line; reading old content suspends follow until 최신 자막. Reflow and original toggles preserve reading position. Retain completed split captions in memory until a new session; history persistence remains opt-in.
- Setup placement selection appears before requirements. SPLIT_SCREEN has audio and model requirements; POPUP additionally requires overlay permission. Popup opacity/geometry controls appear only for POPUP. Switching placement during startup/active/stop is disabled.
- Caption pane offers 함께 볼 앱 선택 and 분할 화면 안내. Explicit app selection only, launchable apps from launcher category (no QUERY_ALL_PACKAGES), request NEW_TASK|LAUNCH_ADJACENT on supported devices. Explain system ratio control and manual Recents fallback; never claim automatic success or arbitrary app compatibility. Existing video task continuity cannot be promised.
- Video/audio capture policy restrictions remain explicit. Choosing a neighbor app does not claim that only that app is captured.
- Korean UI; retain current source-language/model/download/privacy behavior. No model or backend changes. Existing users retain settings and history.
- Validate permission gating, bounded retained captions, rotation/reflow/scroll behavior, one running session, split launch fallback and small panes. Use real build and automated regression evidence; disclose if device testing is unavailable.

Platform reference: https://developer.android.com/develop/ui/views/layout/support-multi-window-mode and https://developer.android.com/media/platform/av-capture (checked 2026-10-16).
