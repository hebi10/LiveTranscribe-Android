# BUBBLE-01 A~F — 플로팅 버블 자막 구현·검증

## 개발 범위
- A: `CaptionPlacement.BUBBLE`, 홈 선택, 버블 전용 DataStore 위치·유지시간·글자 크기·원문 옵션.
- B: `BubbleOverlayController` WindowManager 48dp 원형 버튼. 좌우 고정, 터치 슬롭을 초과한 드래그만 이동, 실제 노출 영역만 터치 차단, 상태바/내비게이션바 제한.
- C: 기존 `CaptionForegroundService`·`OverlayUiState` 흐름에서 실제 확정 번역 하나 표시. 음성 처리 중 직전 자막을 유지하고 원문만 있을 때 번역 자막을 날조하지 않음. 유지시간 3/5/8초, 동일 번역 상태 반복으로 시간이 재시작되지 않음. 기존 2차 교정 범위 색상 강조.
- D: 원형 버튼 클릭 설정: 유지 시간, 글자 크기, 원문, 집중 모드, 위치 초기화, 일시정지/재개, 종료. 기존 집중 모드 설정과 교정 동작 보존. 설정은 홈에서도 조절 가능.
- E: Kotlin 순수 함수·시간 상태·권한 조건 회귀, Android API35 실제 에뮬레이터 WindowManager 합성 번역 전/후 화면 캡처, API29/35/36 홈·기존 화면 테스트.
- F: 최신 PR HEAD CI, OPS-07 정책 검사, Codex 리뷰 및 병합 후 최신 main 재검증. Google Play 업로드는 별도 사용자 승인·수동 Actions 요청으로 분리.

## 보존·제약
- 기본 배치는 기존 `POPUP`, 이전 팝업 설정 `overlayX/Y/Width/Height`와 버블 위치 `bubbleSide/Y` 독립.
- Qwen3-ASR, Hy-MT2, Silero VAD, 모델 다운로드, 마이크·MediaProjection 사용자 동의, 기록 삭제·저장 동작, 서명과 앱 패키지 변경 없음.
- 실제 휴대폰에서 모델 약 2.12GB 설치 및 한국어 번역·발열 검증 전까지 DEV/CORR 및 버블 실음성 QA는 **NOT RUN**.
- 음성을 처리할 때 `recognizing` 또는 `translating` 상태를 사용하므로 별도 VAD 음성 시작 콜백의 정확한 최초 감지 시점은 보장하지 않음. 실제 실기기에서 감지→번역 UI 타이밍을 기록할 것.
- 모든 자동 화면 캡처는 합성 번역 데이터이며 실제 번역 성공의 증거가 아님.
- Google Play 내부 테스트에서 최종 버전을 설치하고 영상 앱의 오디오 캡처 허용 여부, 권한 거부, 화면 회전, 폴더블 화면, 160% 접근성을 확인할 것.

## 실행 확인
- Kotlin: `./gradlew testDebugUnitTest lintDebug assembleDebug` (서명 없는 테스트용)
- Android UI: `UI-GAP-07 final responsive matrix`의 API35 계측에 `BubbleOverlayUiTest` 포함, API29/36 기존 회귀.
- 독립 실제 기기 QA: `docs/qa/QA-REAL-01-B-physical-performance-20261116.md` 사용.
- 최초 화면의 자막 모드 선택·원형 오버레이·시청 집중·기존 팝업·분할 회귀와 모델·기록 데이터 보존을 확인.
