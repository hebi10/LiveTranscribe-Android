# LiveTranscribe 한국어 스토어 이미지 8장 갱신 (2026-11-11)

대상 패키지: `com.hebi.livetranslate`
대상 언어: `ko-KR`
변경 범위: 휴대전화 설명 이미지 8장. 앱 아이콘, 대표 그래픽, 설명 문구, AAB, 서명, 내부/비공개 테스트 트랙은 변경하지 않습니다.

## 자료 및 출처
- 2026-11-11 제작본 Google Drive: https://drive.google.com/file/d/1G5h3Wj5hGvu384AVQo9HCsfWR90ULd8A/view
- 8장 ZIP SHA-256: `8a5a5627feceb4d900460a4bd0bd37f5d8c12ed7a42486042da855e12dc0e5b2`
- 기본 승인 10종 ZIP SHA-256: `5ab7aad8d9dd7d5c7066078fdad4b0bdb843ae2bc722af54946a44f5c6af75fc`
- 최신 8장 모두 1080×1920 RGB PNG이며 2026-11-09 Android 35 에뮬레이터의 앱 화면을 크롭/균일 리사이즈하여 사용했습니다.
- 실제 모델 다운로드, 음성 캡처 및 오프라인 번역 성공 화면은 아닙니다. 이미지로 이를 보증하지 않습니다.
- 01과 03은 기본 앱 화면이 유사하므로 차기 디자인 개선 시 언어 선택부 확대를 검토합니다.

## 등록 검증
1. 기존 고정 승인 ZIP에서 아이콘 및 대표 그래픽을 추출하고 SHA-256을 검사합니다.
2. 11월 11일 8장 ZIP 전체와 각 이미지의 SHA-256, PNG 형식·1080×1920 치수를 검증합니다.
3. 임시 매니페스트에 새 8장만 반영해 등록 후보를 구성합니다. 저장소 기본 기존 매니페스트는 유지합니다.
4. PR과 main Android CI의 `refresh_20261111.py --check` 검사를 통과합니다.
5. 이미 승인된 이미지만 Google Play API로 제출하고 `edits.validate` 및 `edits.commit`과 별도 readback을 확인합니다.
6. Play API의 편집 커밋은 콘솔의 심사/게시/테스터 설치 완료와 구분합니다.

등록 전 원본 8장과 콘솔 등록 이미지가 일치하는지 최종 확인해야 하며, API 편집이 반려되면 중복으로 자동 제출하지 않습니다.
