# Third-party license provenance

Files in this directory are unmodified copies of their official source LICENSE files. Source URLs, exact commits, byte sizes and SHA-256 hashes are in the matching JSON provenance records.

- `sherpa-onnx-LICENSE.txt`: official k2-fsa/sherpa-onnx v1.13.8 release source.
- `Whisper-LICENSE.txt`: official openai/whisper MIT license. The bundled Whisper Tiny int8 ONNX files are pinned separately at csukuangfj/sherpa-onnx-whisper-tiny revision65176e2deb88badc814a94058666cadccc29b61c. That export contains no LICENSE/README; the current official Whisper source commit used for this attribution is independently recorded, not represented as the conversion source commit.

Whisper model file pins used by the app:
- tiny-encoder.int8.onnx:12937772bytes;SHA256d24fb083ae3b1041fc24e97971d60e280c9342201fbb67b0ab428a8b4a51a434.
- tiny-decoder.int8.onnx:89855401bytes;SHA256d2fece8dd42771f1df975c6c0445770d0c292bf7547c2cae04a6c0cc57540925.

Official language identification model documentation: https://k2-fsa.github.io/sherpa/onnx/spoken-language-identification/pretrained_models.html .
