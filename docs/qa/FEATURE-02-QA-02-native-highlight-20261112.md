# FEATURE-02-QA-02 — 네이티브 자막 강조·색상 대비 계측 검증

기준 커밋: `82ec9e94dce5ba275c3617945d1534ff9e81b475` (FEATURE-02 병합 완료).
**범위: 실제 Android 에뮬레이터에서 합성·비식별 한국어 자막의 표시 동작 검증. 오디오 입력, 실제 AI 모델 교정 성공, 실기기 지연·발열은 측정하지 않습니다.**

## 추가 계측 테스트

- `CaptionCorrectionReadingViewTest.changedTokenUsesSpanAndExpiryRestoresThemeColorWithoutReplacingRow`: 분할 자막 네이티브 `CaptionReadingView`를 실제 Android TextView로 생성, 변경된 `3시에` 부분에만 ForegroundColorSpan을 삽입하고 기본 자막 색상을 유지하는지 검증. 만료 후 동일한 TextView와 행 ID가 보존되고 강조가 해제되는지 검증.
- `CaptionCorrectionReadingViewTest.alreadyExpiredChangeNeverAppliesTint`: 이미 경과한 강조 시점에서는 색상 변경이 일어나지 않는지 검증.
- `CaptionCorrectionReadingViewTest.everyUserSwatchHasReadableContrastOnItsActualTheme`: 사용자 선택 6색의 다크(#101010)·라이트(#FFFFFF) 배경 대비율을 4.5:1 이상으로 확인.
- 기존 `CaptionCorrectionSpansTest`: 팝업 공용 텍스트 구간 오프셋과 만료 전후 동작 유지.

## GitHub CI

`.github/workflows/ui-gap-07-final.yml`의 API35 화면/기능 계측 묶음에 `CaptionCorrectionReadingViewTest`를 추가했습니다.
PR의 Private Android APK 빌드·Kotlin JVM·Lint·UI-GAP-07(API29·35·36)·현재 화면 캡처·반응형 QA가 같은 HEAD에서 전부 성공해야 병합 가능합니다.

## 병합·배포

모든 QA가 성공하더라도 실제 기기에서 AI 교정 품질·지연이 검증된 것은 아닙니다.
정확한 SHA 안전 병합 시 커밋 제목에 `[feature-02-qa-only]`를 명시하여 기존 Play 소스 게이트가 `check` 모드로 작동하게 합니다.
앱 Play internal/alpha AAB·스토어 이미지·심사 상태를 변경하지 않습니다.

## 후속 필수 실기기 검증

DEV-01~15, CORR-01~08의 최소 한 대 ARM64 기기 검사, 영어·일본어 실제 음성의 1차/2차 교정, 초록 강조 1초 복원, CPU/PSS·발열·자막 누락 비교가 필요합니다.
실제 사용자가 테스트 증빙을 주기 전에는 실기기 PASS나 Google Play 출시 준비 완료로 판정하지 않습니다.
