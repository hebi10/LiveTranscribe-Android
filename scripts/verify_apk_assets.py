#!/usr/bin/env python3
"""Verify the same bundled-model boundary for debug and signed release APKs."""
import argparse
from hashlib import sha256
from pathlib import Path
from zipfile import ZipFile

import prepare_language_id as language_id
import prepare_speech_support as speech


def verify_apk(path):
    with ZipFile(path) as apk:
        names = apk.namelist()
        if len(names) != len(set(names)):
            raise RuntimeError("Duplicate APK entries cannot be verified")
        expected = {"assets/speech/silero_vad.onnx": (speech.MODEL.size_bytes, speech.MODEL.sha256)}
        expected.update({
            f"assets/language-id/{language_id.MODEL_NAME}/{name}": metadata
            for name, metadata in language_id.MODEL_MANIFEST.items()
        })
        if any(name.startswith("assets/models/") or name.lower().endswith(".gguf") for name in names):
            raise RuntimeError("Large or legacy recognizer/translation models must not be bundled in the APK")
        if any(name.startswith("assets/") and name.endswith(".onnx") and name not in expected for name in names):
            raise RuntimeError("Only speech support and language-ID ONNX assets may be bundled in the APK")
        for name, (size_bytes, fingerprint) in expected.items():
            if name not in names or apk.getinfo(name).file_size != size_bytes:
                raise RuntimeError(f"Packaged support model integrity verification failed: {name}")
            if sha256(apk.read(name)).hexdigest() != fingerprint:
                raise RuntimeError(f"Packaged support model integrity verification failed: {name}")
        if "lib/arm64-v8a/liblivecaption_translation.so" not in names:
            raise RuntimeError("Local translation JNI runtime is missing")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    verify_apk(parser.parse_args().apk)
    print("Verified bundled support model hashes and local runtime; large models require explicit in-app setup")
