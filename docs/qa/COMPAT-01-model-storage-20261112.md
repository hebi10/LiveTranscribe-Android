# COMPAT-01 — 현재 오프라인 AI 모델 용량 기준 기기 저장 공간 안내

기준: `82ec9e94dce5ba275c3617945d1534ff9e81b475` (FEATURE-02 병합 완료).
상태: 브랜치·CI 검증 대기. **모델 실기기 다운로드/번역 성능 검증과는 별개**입니다.

## 기존 문제
- `DeviceCompatibilityChecker`의 기존 350MB 최소·1.5GB 권장 기준은 과거 Vosk/ML Kit 모델 시기의 값입니다.
- 현재 다운로드 목록은 `OfflineModelCatalog.files`의 고정 모델 7개, 약 2.12GB이며 `OfflineModelStore.prepare()`는 실제 미다운로드 바이트+16MiB 여유를 검사합니다.
- 호환성 검사 상수만 바라보면 다운로드 가능한 것으로 오인할 수 있습니다.

## 수정 범위
1. `DeviceCompatibilityChecker.MIN_STORAGE_MB`를 현재 파일 목록 합계+16MiB에서 자동 계산합니다. 권장값은 추가 512MB 여유.
2. 이미 SHA-256 검증된 모델이 있는 경우에는 **현재 남은 저장 공간이 다운로드 초기 공간 기준보다 작다는 이유만으로** 기기를 지원 불가로 분류하지 않습니다.
3. 저장 공간을 측정할 수 없는 경우 1GB라고 추측하지 않고 미확인 경고로 분류합니다.
4. 첫 실행 모델 준비 UI에서 현재 `noBackupFilesDir.usableSpace`와 모델 다운로드 진행량으로 예상 추가 필요량을 보여 줍니다. 부족하면 오류색으로 구분하되 기존 사용자 다운로드 버튼은 유지하고, 실제 차단 여부는 `OfflineModelStore.prepare()`가 판단합니다.
5. API·화면·단위 테스트에 새로운 권한이나 의존성을 추가하지 않습니다.

## QA 기준
- [ ] `DeviceCompatibilityTest`: 실제 모델 목록에서 계산한 최소치, 32bit/CPU/RAM 테스트 보존, 모델 기설치 예외, 알 수 없는 용량 검사
- [ ] `ModelStorageNoticeTest`: 새 다운로드, 이어받기, READY, CHECKING, 저장 공간 미측정
- [ ] Android 빌드·Lint·단위 테스트 성공
- [ ] API29/35/36 화면 캡처, 폰/태블릿·160% 환경 성공
- [ ] ARM64 실기기에서 모델 설치 전후 실제 저장 공간 표시와 다운로드 실패/이어받기 수치 대조
- [ ] Play 내부/비공개 트랙은 `[compat-01-qa-only]` 커밋 보호 마커로 자동 배포 방지

## 후속
`QA-REAL-01` 실기기 실행에서 실제 용량, 2차 AI 교정 품질·성능, 저장 공간 부족 시 오류 복구를 확인합니다. 실기기 결과 없이 모든 검증 PASS 주장 금지.
