package com.charles.livecaptionn.models

/** Immutable official/maintainer revisions. Preparation verifies every file against its pinned SHA-256. */
object OfflineModelCatalog {
    val files: List<OfflineModelFile> = listOf(
        OfflineModelFile(
            "qwen_frontend", "qwen3-asr/conv_frontend.onnx",
            "https://huggingface.co/csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/resolve/68818b2313fe77bd06f6a7c5068ff3ef59d02b8a/conv_frontend.onnx",
            44148281L, "d22dc4423e0940e49884e903d2ea2f7e5567c14fc1aed97e4e26d6b8f208ef9e"
        ),
        OfflineModelFile(
            "qwen_decoder", "qwen3-asr/decoder.int8.onnx",
            "https://huggingface.co/csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/resolve/68818b2313fe77bd06f6a7c5068ff3ef59d02b8a/decoder.int8.onnx",
            755914231L, "4f6885be5959ae26af3089d38ee7972c5fafbeeb1cf8d5e76eab6d8b61ca5771"
        ),
        OfflineModelFile(
            "qwen_encoder", "qwen3-asr/encoder.int8.onnx",
            "https://huggingface.co/csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/resolve/68818b2313fe77bd06f6a7c5068ff3ef59d02b8a/encoder.int8.onnx",
            182491662L, "60748d3e6744a57c9c91e1b17424a6c2990567e8adceb0783940c03ed98fa9d9"
        ),
        OfflineModelFile(
            "qwen_merges", "qwen3-asr/tokenizer/merges.txt",
            "https://huggingface.co/csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/resolve/68818b2313fe77bd06f6a7c5068ff3ef59d02b8a/tokenizer/merges.txt",
            1671853L, "8831e4f1a044471340f7c0a83d7bd71306a5b867e95fd870f74d0c5308a904d5"
        ),
        OfflineModelFile(
            "qwen_config", "qwen3-asr/tokenizer/tokenizer_config.json",
            "https://huggingface.co/csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/resolve/68818b2313fe77bd06f6a7c5068ff3ef59d02b8a/tokenizer/tokenizer_config.json",
            12487L, "4942d005604266809309cabc9f4e9cb89ce855d59b14681fdc0e1cc62ea26c4c"
        ),
        OfflineModelFile(
            "qwen_vocab", "qwen3-asr/tokenizer/vocab.json",
            "https://huggingface.co/csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/resolve/68818b2313fe77bd06f6a7c5068ff3ef59d02b8a/tokenizer/vocab.json",
            2776833L, "ca10d7e9fb3ed18575dd1e277a2579c16d108e32f27439684afa0e10b1440910"
        ),
        OfflineModelFile(
            "hymt_model", "translation/Hy-MT2-1.8B-Q4_K_M.gguf",
            "https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF/resolve/a0c709d9fac510f2c807aa3af52872340dc37a4a/Hy-MT2-1.8B-Q4_K_M.gguf",
            1133080448L, "dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699"
        )
    )
}
