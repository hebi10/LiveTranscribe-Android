# Play Actions 직접 업로드 검증

## 범위

기존 `Play internal release`의 `check / build / deploy` 흐름을 유지한다. `check`에서도 전용 배포 인증과 이 앱의 Play 접근 여부를 확인하고, 실제 AAB 업로드는 Google이 반환한 SHA-256까지 비교한다. 최초 Console 업로드 요건과 `PLAY_CONSOLE_INITIALIZED` 배포 제한은 유지한다.

## 작업

1. 업로드·커밋 없이 편집을 열고 번들·APK·트랙을 조회한 뒤 닫는 `inspect` 명령을 추가한다. HTTP 상태만 안전한 결과에 기록하며, 빈 앱을 등록 완료로 판단하지 않는다.
2. `check` 작업에 `google-play` 환경과 단기 WIF 인증을 연결한다. 초기화 변수와 독립적으로 진단하되 배포·초기화 변수 쓰기는 하지 않는다.
3. AAB 업로드 응답의 SHA-256을 로컬 파일과 비교하고 누락·형식 오류·불일치 시 트랙 변경·커밋을 차단한다.
4. 최초 Console 등록, 내부 테스트 기본 배포, 명시적 비공개 승격 절차를 문서화한다.

## 검증

편집 정리와 미커밋, 403/404 상태 보존 및 비밀값 비노출, 초기화 제한 유지, 업로드 해시 불일치 차단을 테스트한다. 전체 Python 검사와 actionlint, YAML 파싱, diff 검사를 실행한다. 실제 Play 인증·업로드 여부는 이후 Actions 실행 결과로 별도 확인한다.
