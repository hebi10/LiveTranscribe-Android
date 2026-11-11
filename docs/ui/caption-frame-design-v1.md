# CAPTION FRAME 공통 디자인 기준 v1.0 — UI-GAP-01

- 확정일: 2026-11-11 (KST)
- 프로젝트: `hebi10/LiveTranscribe-Android` / `com.hebi.livetranslate`
- **근거 코드**: `main@e79f870bf24d398f3acc845cc21520c476f7d9dd`
- **근거 이미지**: UI-GAP-00 Android 35 `com.hebi.livetranslate.debug` 대기/팝업 9장 + 합성 기록 QA 4장 + 반응형 6장 = 19장. 실제 AI 추론 성공은 **검증하지 않았음**.
- **기준 JSON**: [`caption-frame-tokens-v1.json`](./caption-frame-tokens-v1.json). 색상은 실제 `CaptionFrameTheme.kt`에 선언된 이름을 기준으로 검증한다.
- **이번 PR 범위**: 디자인 규칙·구현 대조·회귀 검사만 고정한다. 앱 UI, 런처 PNG, 모델, 사용자 데이터, 앱 패키지, 배포 트랙을 수정하지 않는다.

## 1. 최종 디자인 의사결정

CAPTION FRAME — ‘영상은 그대로, 자막은 선명하게.’ **오디오 인식→한국어 번역→분할/팝업 자막**을 방해하지 않는 작업 도구형 인터페이스를 기준으로 확정한다.

- **유지**: 시스템/라이트/다크 사용자 선택, Neutral Black/White/Gray, 평면형 선형 아이콘, 의미 기반 오류 색상, `RectangleShape` 중심의 컨트롤, 오버레이의 독립 어두운 테마.
- **배제**: 과거 Instant Translator 디자인의 `Screen #2F8CFF / Voice #7C4DFF / Integrated #12B981` 등 기능별 색상, 3D 재질, 네온·그라데이션, 과도한 라운드/그림자 및 별도의 바텀 내비게이션.
- **중요한 차이**: 과거 스플래시/메인/권한/기록 시안 속 화면 OCR 번역·음성/화면 통합 모드는 이 앱의 현재 구현 범위가 아니므로 누락된 기능으로 세지 않는다.
- 오류·실패의 명확한 식별에만 빨강 계열을 사용한다. 일반적인 선택·비활성·준비·완료 상태를 색상 하나로만 표현하지 않는다.

## 2. 색상 — 실제 Kotlin 토큰 일치 (확정)

| 의미/Material3 role | Light | Dark | 구현 |
|---|---|---|---|
| 화면 배경 `background` | `#FFFFFF` | `#101010` | 동일 |
| 기본 표면 `surface` | `#FFFFFF` | `#101010` | 동일 |
| 구획/카드 `surfaceContainer` | `#F5F5F5` | `#202020` | 동일 |
| 본문 `onBackground/onSurface` | `#171717` | `#F5F5F5` | 동일 |
| 보조 `onSurfaceVariant` | `#666666` | `#B5B5B5` | 동일 |
| 구분 `outlineVariant` | `#DEDEDE` | `#404040` | 동일 |
| 주요 액션 `primary` | `#191919` | `#F1F1F1` | 동일 |
| 주요 버튼 텍스트 `onPrimary` | `#FFFFFF` | `#171717` | 동일 |
| 오류 `error` | `#B42318` | `#FF8D82` | 동일 |

사용처: `app/src/main/java/com/charles/livecaptionn/ui/theme/CaptionFrameTheme.kt`.
시스템 상태바/탐색바는 실제 선택한 라이트·다크 밝기에 맞추며, Overlay는 영상 위 가독성 때문에 앱의 라이트 선택에 무조건 따라가지 않는다.

## 3. 타이포그래피 (후속 화면 작업에 적용할 고정 규격)

Android 시스템 Sans/한글 fallback 사용. 새 폰트 라이브러리·로컬 폰트 파일 추가 없음. **현재 앱은 Material3 기본 `MaterialTheme.typography`를 사용하고 별도 `Typography` 객체는 선언하지 않는다.** 아래는 기존 Material3 role 기반 확정 사용 가이드로, 모든 화면에 이미 정확히 적용됐다는 뜻이 아니다.

| 역할 | Compose 기준 | 크기/의도 |
|---|---|---|
| 화면 제목 | `titleLarge`, 필요 시 `headlineSmall` | 22sp / 24sp |
| 섹션 제목 | `titleMedium` | 16sp (굵기 일관 적용) |
| 주요 설명·중요 값 | `bodyLarge` | 16sp |
| 기본 본문·상태 | `bodyMedium` | 14sp |
| 보조 안내·보조 메타 | `bodySmall` | 12sp; 과도한 정보는 접기 |
| 버튼 텍스트 | `labelLarge` | 14sp |

기기의 `fontScale=1.6`에서도 줄바꿈과 스크롤 접근이 유지되어야 한다. `sp` 값을 고정 픽셀처럼 다루거나 화면 좁힘으로 잘라서는 안 된다.

## 4. 간격·형태·터치

- 기본 간격 단위 **4dp**, 선호 간격 **4/8/12/16/24/32dp**. 화면 좌우 여백 **16dp**, 섹션 간 **16dp**, 정보 행 간 **8dp**, 카드 안쪽 **12~16dp**를 기준으로 확정.
- 주요 컨트롤은 **최소 48dp의 터치 가능한 영역**을 목표로 한다. 실제 Overlay는 `TOUCH_DP=48`, `MINIMIZED_HEIGHT_DP=56`, 라벨 컨트롤 폭 최소 64dp 구현됨.
- 앱 UI에서 새 영역은 **`RectangleShape`**, 별도 그림자/그라데이션 없음. 시스템 소유 권한·MediaProjection 대화상자는 앱 디자인으로 재작성하지 않는다.
- 일반 컨트롤에 한쪽 면만 따로 강조하는 테두리를 추가하지 않는다. 그룹 구분은 **공통 구분선, 배경 차이, 충분한 여백**으로 처리.
- **현재 코드 불일치**: `MainScreen.kt`에는 6dp/10dp 간격이 남아 있고 일부 `FilledTonalButton/OutlinedButton`에 명시적 48dp 높이가 없다. 이는 **UI-GAP-02/03의 화면별 수정 항목**이며 이번 단계에서 임의로 레이아웃을 바꾸지 않는다.

## 5. 아이콘·Overlay 공통 규칙

- Android 런처: `ic_caption_frame_mark`를 Adaptive Icon `foreground` 및 `monochrome`에 재사용. 배경은 중립색; 3D 광택/추가 장식 금지.
- 앱 기능 아이콘: Material Outlined 계열의 동일한 시각 무게. 기존 `Subtitles/History/VerticalSplit/PictureInPictureAlt` 등과 팝업 아이콘의 기능 의미가 일치하도록 유지한다.
- **상태 표시**: 아이콘 하나·색상 하나만으로 정보 제공 금지. 시작 가능/준비 중/일시정지/종료는 텍스트 또는 접근성 이름을 유지한다.
- `OverlayThemeCatalog`의 기본 색은 `#101010` + 흰 글자; 고대비는 검정 + 흰 글자. 앱이 라이트 모드여도 영상 위 팝업은 어두운 기본 옵션을 보존한다.
- 일반/펼침/최소화 3상태 모두 기존 이동·크기·원문·최근 자막·종료 기능을 유지. 좁은 화면에서 조작부의 실제 영상 가림 정도는 **UI-GAP-05 실기기 검증**으로 남긴다.

## 6. 화면 컴포넌트 계약

| 요소 | 확정 디자인/동작 | 확인 및 후속 |
|---|---|---|
| 상단 앱 바 | ‘실시간 영상 번역’ + 번역 기록 진입, 중립색 아이콘 | 이미 구현 |
| 실행 CTA | 홈 하단 고정 1차 액션, 텍스트로 준비/실행 구분, 48dp 이상 | 이미 고정 구현; 준비 안내 축소는 `UI-GAP-02` |
| 준비 영역 | `0/3`·`1/2`·실행 가능 등 현재 필요한 다음 단계 1개 강조 | 정보 밀도 `UI-GAP-02/03` |
| 화면 배치·입력·언어 | 선택됨/선택 안 됨 명확히 구별; 라벨 필수 | 버튼 높이·간격 `UI-GAP-02` |
| 분할 자막 | 번역문 우선, 원문 보조, 최신 이동·설정·중단 보존 | 실문장 `UI-GAP-04/08` |
| 팝업 | 불투명도·이동/리사이즈/최소화 보존, 영상 가림 최소화 | 실기기 `UI-GAP-05` |
| 기록 | 기본 저장 OFF, 검색/삭제와 빈 상태 구분 | 합성 이외 실기록 `UI-GAP-06/08` |
| 안내/다이얼로그 | 제목·본문·취소·위험한 삭제 명확히 구분 | `UI-GAP-06` |

## 7. UI-GAP-00 실화면 대조 결과

기준 원본은 **Android 35 x86_64**, 독립 테스트 패키지 `com.hebi.livetranslate.debug`, `main@e79f870bf24d398f3acc845cc21520c476f7d9dd`.
- **13장**: 기본 대기 6장, WindowManager 팝업 3장, 합성 테스트 기록 QA 4장.
- **6장**: 소형 `840×1480/fontScale 1.6`, 가로 `1920×1080`, 태블릿 `1600×2560`의 홈/설정.
- 화면은 대기 UI에서 모델 권한/설치 안내가 첫 화면 상당 영역을 차지함. 160% 글꼴에서는 준비 상태와 설정 설명이 여러 줄로 늘어나 스크롤이 많아진다.
- 팝업 헤더/확장 조작 영역이 큰 편이며 실제 영상 위 점유도는 추가 측정 필요.
- 합성 기록 QA PNG는 **실제 생성된 번역 기록이 아니므로 Google Play 홍보 이미지로 사용 금지**.
- 원본/해시 검증 및 자세한 상태 목록: [Google Docs UI-GAP-00 보고서](https://docs.google.com/document/d/1xb5hbwAZRcClrobZjCndg9iKiX_ZssayePfdiJ3loGw/edit).
- [원본 13장 ZIP](https://drive.google.com/file/d/1Af0rzMab1WTxjxaO8KroXvOUgZB6qbf0/view) / [반응형 6장 ZIP](https://drive.google.com/file/d/1b5FHHJ7bnRd5JLGxvfxvNNPPLUtVcpdF/view).

## 8. 확정 범위·남은 QA

**이번 UI-GAP-01 완료 기준**: 디자인 의사결정과 앱/Drive 근거, JSON 토큰, 기존 코드 회귀 검증 및 GitHub 병합·Drive 사본 등록. 실제 화면 수정·새 Play 배포는 이 단계의 완료 기준이 아니다.

이 단계에서 이미 확인된 코드: `CaptionFrameTheme` Light/Dark, `AppThemeMode` SYSTEM/LIGHT/DARK, Adaptive Icon monochrome, `OverlayChromeMetrics` 최소 터치. **미완료**: 모든 화면의 4dp 간격·48dp 터치 준수, 기기별 160% 글꼴의 모든 상태, 실제 모델/음성 번역, API 29/36·폴더블·실기기, 스토어 8장 중복 01/03.

**다음: `UI-GAP-02 — 홈·번역 시작 UI 정밀화`**
1. 불필요하게 큰 준비 카드 압축, 현재 필요한 한 가지 권한/모델 행동을 우선 표시.
2. 화면 배치→동영상 음성/마이크→인식 언어 순서 유지하면서 레이아웃·버튼 48dp 및 4dp 간격 체계 정리.
3. 하단 고정 ‘시작/준비’ CTA와 기존 권한·모델 시작 게이트 보존.
4. 일반·좁은 화면(160%)·가로·태블릿의 라이트/다크 스크린샷 및 Android CI 재검수.

## 출처 링크

- [기존 CAPTION FRAME 기획](https://docs.google.com/document/d/1g6vRMtgM5g4X2NQh1zxkgyPft6Fq4Yaksg2y-mZCS-M/edit)
- [UI-GAP-00~10 전체 단계별 계획](https://docs.google.com/document/d/1kpOfqV5kyjhLshw-z59CSRWM8nkIBabgtTZ9tnycgIU/edit)
- [Drive 디자인 시스템 폴더](https://drive.google.com/drive/folders/1Ny8OMKPVmvHMBtR9MMoyhwiJGfDlMhf9)
- [Google Docs UI-GAP-01 기준 문서](https://docs.google.com/document/d/1UPTsCjUn_hoeF7Mh9jdYPJWBiSPfkU45use1OLZ8m9M/edit)
- [Android 캡처 CI](https://github.com/hebi10/LiveTranscribe-Android/actions/runs/42966363003) 및 [반응형 CI](https://github.com/hebi10/LiveTranscribe-Android/actions/runs/42966362981)
