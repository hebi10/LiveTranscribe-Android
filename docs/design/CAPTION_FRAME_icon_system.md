# CAPTION FRAME — 1-B 아이콘 기준

- 기준 테마: CAPTION FRAME (1-A, 흑백/중성 회색).
- 브랜드 원본: 이미지 생성 시안에서 추출한 흰색 자막 프레임 + 두 줄 자막 + 재생 삼각형. 추가 외부 이미지 사용 없음.
- Android Adaptive Icon: 투명 432×432 PNG(중앙 안전 영역) + 단색 #101010 배경.
- `ic_launcher.xml` / `ic_launcher_round.xml`: 동일 전경과 배경 사용.
- Android 13+ monochrome: 같은 알파 실루엣 사용. 미지원 기기는 일반 Adaptive Icon 표시.
- Compose: Filled/Default 혼용 제거, Material Icons Outlined 통일. 홈 헤더는 Outlined.Subtitles.
- Overlay: 기존 WindowManager용 24dp 선형 벡터 리소스 유지, 2dp stroke 정렬.
- 알림: Android 기본 아이콘 대신 프로젝트의 자막/일시정지/닫기 선형 리소스 사용.
- 시각적 동작(시작/중지/권한/저장/페이지 이동) 및 콘텐츠 설명 유지.
- `assets/branding/caption-frame-store-icon-512.png`는 차후 6-A 스토어 교체 작업에 사용할 브랜드 시안이며 현재 등록된 스토어 에셋을 교체하지 않는다.
- 원본 시안 및 PNG 에셋: 구글 드라이브 `06_LiveTranscribe_실시간번역/02_디자인/001_CAPTION_FRAME_브랜드_아이콘`에 보관.
- 주의: 이 PR은 새 UI 화면 레이아웃을 구성하거나 실제 번역 기능을 변경하지 않는다. 전체 캡처는 후속 5-A에서 검증한다.
