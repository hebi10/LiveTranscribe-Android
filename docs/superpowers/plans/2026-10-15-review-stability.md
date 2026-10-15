# 리뷰 안정화 구현 계획

**Goal:** 1.0.5 전체 검토에서 확인한 음성·기록·권한·모델·팝업 결함을 수정하고 검증된 1.0.6을 제공한다.
**Architecture:** Kotlin/Compose와 기존 service/engine/store 구조를 유지한다. 새 제품 의존성 없이 소유권·상태 반영 시점과 파일 검증을 수정한다.
**Approved scope:** 사용자가 전체 리뷰의 개선 방향을 승인했다. 앱의 음성·문장 외부 전송은 추가하지 않는다.

## 공통 제약
- 일본어 원본 PCM 최대8초, 영어6초 및 인식/번역 대기2개 제한을 유지한다.
- 무음과 독립적인 매우 짧은 잡음 필터는 유지하며, 강제 경계 뒤 이어진 음성을 누락하지 않는다.
- 기존 설정과 기록을 보존하며 관련 없는 UI·네이밍은 변경하지 않는다.
- 새 녹음 업로드/진단 수집 기능, 새 런타임 의존성을 추가하지 않는다.
- 기기 내 직접 번역 모델은 검증 없는 교체를 하지 않는다. 이번 수정의 인식/번역 품질 개선 범위와 실기기 미검증을 명시한다.

## 작업 및 검증
- [x] 음성: JapanesePcmSegmenter의 강제8초 경계 뒤100ms/200ms tail과 stop/pause/silence 보존 회귀. 독립100ms 잡음은 여전히 제외. StreamingSttEngine의 pause/resume drain 및 진행중 read 무효화를 양 언어에 적용하고 Vosk 경계 분리. 소유 파일 speech/ 및 해당 테스트.
- [x] 서비스/기록: 준비 중 pause 요청을 새 engine에 적용. 번역 시작 당시 설정 대신 저장 직전 동의와 설정 변경을 같은 gate로 동기화. 동의 해제 후 대기 저장 차단 회귀. 소유 파일 service/CaptionForegroundService.kt, di/AppContainer.kt, data/HistoryRecordingGate.kt, ui/MainViewModel.kt 및 관련 테스트.
- [x] 기록/권한 UI: 기록 전체 펼치기/접기, 저장 실패를 오류 안내+재시도로 처리하고 취소는 전파. 영구 권한 거부 시 앱 설정 이동 및 복귀 상태 갱신. 소유 파일 ui/HistoryScreen.kt, MainActivity.kt, ui/MainScreen.kt, ui/CaptionStartGate.kt 및 새로운 UI helper/tests. MainViewModel.kt 수정은 root에 요청.
- [x] 팝업: preferred normal geometry와 화면에 맞춘 display geometry 분리. 구성 변경은 선호값을 덮지 않음. 화면 읽기용 상하좌우 이동 동작 추가, 범위 제한과 기존 resize/scroll 보존. 소유 파일 overlay/OverlayController.kt, OverlayGeometry.kt 및 관련 테스트.
- [x] 모델: Whisper 파일별 크기/SHA256 고정·손상 재다운로드·중단 후 복구·임시파일 후 atomic replace. 동일 검증 방향을 영어 Vosk 준비에도 적용할 수 있으면 적용. CI 검사와 THIRD_PARTY_NOTICES 일치. 소유 파일 scripts/prepare_language_id.py, prepare_vosk_models.py, 관련 tests, .github/workflows/build.yml, THIRD_PARTY_NOTICES.md.
- [x] 검증: 현재 source와 최신 main 일치 확인, 의미 있는 실패→성공 회귀, 전체 Android unit/lint/build 및 Python/privacy 검증, APK CRC/model/signature/permissions 검사, 독립 diff 리뷰.
- [ ] 배포: version1.0.6/code7, README 제한/변경 반영, PR CI 성공 후 승인된 main 병합, 테스트 소스 일치 검증, APK 저장·전달.

## 검토 중점
- stop/cancel 중 native resource를 실행 중 해제하지 않는다.
- pause가 setup 중 발생해도 캡처를 시작하지 않는다.
- 저장 끄기는 기존 기록 삭제가 아니며 이후 commit을 차단한다.
- 삭제 실패를 성공으로 표현하지 않는다.
- 회전/접힘·접근성 이동·사용자 resize가 서로 선호값을 덮지 않는다.

## 로컬 검증 결과
- Android 단위 테스트 263개(35개 suite), Python 회귀 32개 통과. `testDebugUnitTest lintDebug assembleDebug` 성공.
- APK 모델 21개 고정 크기/해시, 전체 CRC, arm64 라이브러리 9개 ELF 및 ZIP 16KiB 정렬, 서명 검증 통과.
- 독립 코드 리뷰에서 미해결 P1/P2 없음. 실기기 음성·접근성·발열·번역 품질 검증은 수행하지 않았다.
- 이전 배포 APK의 개인 서명키가 현재 환경에 없어 기존 앱을 유지하는 별도 패키지의 검증용 APK를 제공한다. 저장소 applicationId는 유지하며 외부 빌드 설정에만 검증용 ID/라벨/arm64 필터를 적용한다.
