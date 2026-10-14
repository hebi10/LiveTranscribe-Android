#!/usr/bin/env python3
"""Bundle language-ID model and pinned Android AAR at APK BUILD time, not on the phone."""
from __future__ import annotations

from hashlib import sha256
from pathlib import Path
from tempfile import TemporaryDirectory
from urllib.request import urlopen
import shutil
import tarfile

ROOT = Path(__file__).resolve().parents[1]
VERSION = "1.13.8"
AAR_SHA256 = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
AAR_PATH = ROOT / "app" / "libs" / f"sherpa-onnx-{VERSION}.aar"
MODEL_NAME = "sherpa-onnx-whisper-tiny"
MODEL_DIR = ROOT / "app" / "src" / "main" / "assets" / "language-id" / MODEL_NAME
MODEL_FILES = ("tiny-encoder.int8.onnx", "tiny-decoder.int8.onnx")

def download(url: str, destination: Path) -> None:
    with urlopen(url, timeout=180) as response, destination.open("wb") as output:
        shutil.copyfileobj(response, output, length=1024 * 1024)

def ensure_aar(tmpdir: Path) -> None:
    if AAR_PATH.exists() and sha256(AAR_PATH.read_bytes()).hexdigest() == AAR_SHA256:
        print("Sherpa-ONNX Android AAR already prepared")
        return
    AAR_PATH.parent.mkdir(parents=True, exist_ok=True)
    source = tmpdir / "sherpa.aar"
    download(
        f"https://github.com/k2-fsa/sherpa-onnx/releases/download/v{VERSION}/sherpa-onnx-{VERSION}.aar",
        source,
    )
    if sha256(source.read_bytes()).hexdigest() != AAR_SHA256:
        raise RuntimeError("Sherpa-ONNX AAR SHA-256 mismatch")
    shutil.move(source, AAR_PATH)
    print("Prepared verified Sherpa-ONNX Android AAR")

def ensure_models(tmpdir: Path) -> None:
    if all((MODEL_DIR / f).is_file() and (MODEL_DIR / f).stat().st_size > 1024 for f in MODEL_FILES):
        print("Whisper Tiny language ID models already prepared")
        return
    archive = tmpdir / "model.tar.bz2"
    download(
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"
        + MODEL_NAME + ".tar.bz2",
        archive,
    )
    MODEL_DIR.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive, mode="r:bz2") as bundle:
        for name in MODEL_FILES:
            member_path = f"{MODEL_NAME}/{name}"
            member = next((m for m in bundle.getmembers() if m.name.removeprefix("./") == member_path), None)
            if member is None or not member.isfile() or member.size < 1024:
                raise RuntimeError(f"Missing required language-ID model: {name}")
            source = bundle.extractfile(member)
            if source is None:
                raise RuntimeError(f"Unable to extract {name}")
            with source, (MODEL_DIR / name).open("wb") as target:
                shutil.copyfileobj(source, target)
    print("Whisper Tiny language ID model is bundled (no app download)")

if __name__ == "__main__":
    with TemporaryDirectory() as tmp:
        tmpdir = Path(tmp)
        ensure_aar(tmpdir)
        ensure_models(tmpdir)
