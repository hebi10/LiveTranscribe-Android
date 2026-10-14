# 일본어 음성 인식 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 일본어 인식을 ReazonSpeech로 개선하고 처리 지연과 원문 형식을 검증한 APK를 전달한다.
**Architecture:** 기존 캡처 소유권을 유지한다. 일본어 음성 구간 생성과 native 추론을 분리하고, 영어 Vosk와 기존 ML Kit 번역은 그대로 사용한다.
**Tech Stack:** Kotlin, Android API29–36, coroutines1.8.1, sherpa-onnx1.13.8, Vosk0.3.75, ML Kit17.0.3.
**Spec:** docs/superpowers/specs/2026-10-14-japanese-asr-design.md

## Global Constraints
- PCM16 mono 16000Hz, 일본어 구간 8초·영어 6초, ASR 대기2개+진행1개.
- Silero threshold0.5/window512/minSilence1.0초/minSpeech0.25초. 일본어 leading buffer0.5초, decoder-only padding0.5초.
- 제품 의존성 추가 없음. 음성·원문 업로드, 음성 파일 저장, 원문 로그 없음.
- 기존 source/translation pairing, history opt-in, immutable completed captions 유지.

## Review Focus
- 무음·잡음에서 임의 원문이 만들어지지 않도록 음성 감지로 추론을 제한한다.
- 연속 발화·실제 partial read는 메모리 상한을 지키며 8초 경계를 넘지 않는다.
- 일시정지 전후 음성이 혼합되지 않고 상태는 PAUSED를 유지한다.
- 모델 생성·추론 중 취소에도 native 자원을 추론 중 해제하지 않는다.
- 음성 인식 대기 생략이나 최종 tail이 기존 캡처 오류를 지우지 않는다.

### Task 1: 모델 비교와 준비
**Files:** scripts/prepare_japanese_asr.py, scripts/test_prepare_japanese_asr.py, scripts/prepare_vosk_models.py, .github/workflows/build.yml, speech/VoskModelCatalog.kt, speech/VoskModelRegistry.kt, VoskModelCatalogTest.kt.
**Interfaces:** assets/models/reazonspeech-k2-v2/{encoder.int8.onnx,decoder.int8.onnx,joiner.int8.onnx,tokens.txt,silero_vad.onnx}; engine REAZON. resolveModelDir returns null for asset-manager models.
- [x] 공개 음성의 실제 native 비교와 padding/VAD 분할 결과를 확인한다.
- [x] 캐시 손상·hash 불일치·중단 다운로드·필수 asset 누락의 실패 검사를 먼저 실행한다.
- [x] pinned SHA/size 검증, 원자적 준비, CI 및 모델 준비 상태를 구현하고 Python/JUnit 검사를 실행한다.

### Task 2: 일본어 음성 처리
**Files:** JapaneseSpeechPipeline.kt, JapanesePcmSegmenter.kt, LocalJapaneseSttClient.kt, StreamingSttEngine.kt 및 각 회귀 테스트.
**Interfaces:** feed(pcm,length) returns boundary synchronously; finish suspend drains; onSkippedAudio reports overflow. ASR finals use SpeechResult(text,true).
- [x] 무음·길이 상한·silence boundary·pause·tail-once·overflow·cancel lifecycle 검사를 먼저 실행한다.
- [x] VAD와 native recognizer의 독립 소유권, 별도 IO 작업, 음성 구간 메모리 상한을 구현한다.
- [x] native 실패가 ERROR로 전달되고 이후 LISTENING으로 덮이지 않는지 검토한다.

### Task 3: 원문과 상태 통합
**Files:** JapaneseTranscriptNormalizer.kt와 테스트, CaptionForegroundService.kt, CaptionRuntimeStore.kt, ServiceTransforms.kt, OverlayUiState.kt, OverlayController.kt, MainScreen.kt 및 기존 transform 테스트.
**Interfaces:** normalizeRecognizedTranscript(text,languageCode); recordSkippedSpeechSegment(runtime); caption backlog notice survives successful capture events.
- [x] 일본어/혼합 문자 정규화, 인식 생략 안내, pause-final 상태와 오류 보존 실패를 먼저 확인한다.
- [x] 인식 결과를 display/translation/history 전에 정리하고 인식 생략을 별도 카운트로 안내한다.
- [x] 모델 상태 UI와 일본어 구간 확정 설명, version1.0.5/code6, README를 반영한다.
- [x] 전체 Android/Python 검사, APK CRC/asset/signature 검사와 코드 리뷰를 완료한다.
- [ ] GitHub PR CI 성공 후 main에 병합하고 검증된 APK를 저장·전달한다.
