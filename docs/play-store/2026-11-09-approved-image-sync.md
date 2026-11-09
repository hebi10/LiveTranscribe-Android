# CAPTION FRAME 한국어 Google Play 이미지 등록 요청 — 2026-11-09

- 대상 앱: `com.hebi.livetranslate`, `ko-KR`
- 승인된 스토어 이미지: 앱 아이콘 1개(512×512), 대표 그래픽 1개(1024×500), 휴대전화 스크린샷 8개(1080×1920)
- 저장소 고정 아카이브: `assets/play-store/approved/LiveTranscribe_10_assets_20261109.zip`
- SHA-256: `5ab7aad8d9dd7d5c7066078fdad4b0bdb843ae2bc722af54946a44f5c6af75fc`
- 이미지 소스: Android PR #32 CAPTION FRAME 실화면 캡처, 실제 음성 인식·번역 결과는 아님
- 범위: 기존 스토어 이미지 세 종류만 갱신; 앱 바이너리, 서명키, 릴리스 트랙, 스토어 설명문, 타 언어 등록정보 불변
- GitHub Actions: 이미지 업로드 뒤 Play API `edits.validate` 및 `edits.commit` 단계 구분. API 검증 실패 시 변경사항을 게시했다고 판단하지 않음

## 실행 조건

이 문서와 함께 기록하는 `[sync-play-images]` 커밋 마커는 기존 사용자 승인 이미지 세트를 확인된 `main` Android CI 통과 후 한 번 제출하도록 허용합니다. 이미지 변경사항의 심사 승인·실제 게시 상태는 별도 검증 대상입니다.
