#!/usr/bin/env python3
"""Bundle language-ID model and pinned Android AAR at APK BUILD time, not on the phone."""
from __future__ import annotations

from hashlib import sha256
from pathlib import Path
from tempfile import TemporaryDirectory
from urllib.request import urlopen
import argparse
import shutil
import tarfile

ROOT = Path(__file__).resolve().parents[1]
VERSION = "1.13.8"
AAR_SHA256 = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
AAR_PATH = ROOT / "app" / "libs" / f"sherpa-onnx-{VERSION}.aar"
MODEL_NAME = "sherpa-onnx-whisper-tiny"
MODEL_DIR = ROOT / "app" / "src" / "main" / "assets" / "language-id" / MODEL_NAME
MODEL_FILES = ("tiny-encoder.int8.onnx", "tiny-decoder.int8.onnx")
# Measured from the official asr-models release archive on 2026-10-15.
# Source/provenance: https://k2-fsa.github.io/sherpa/onnx/spoken-language-identification/pretrained_models.html
# Independently matched to the documented csukuangfj/sherpa-onnx-whisper-tiny
# Hugging Face LFS SHA-256/size metadata at 65176e2deb88badc814a94058666cadccc29b61c.
# Archive SHA-256: c46116994e539aa165266d96b325252728429c12535eb9d8b6a2b10f129e66b1
MODEL_MANIFEST = {
    "tiny-encoder.int8.onnx": (12937772, "d24fb083ae3b1041fc24e97971d60e280c9342201fbb67b0ab428a8b4a51a434"),
    "tiny-decoder.int8.onnx": (89855401, "d2fece8dd42771f1df975c6c0445770d0c292bf7547c2cae04a6c0cc57540925"),
}


def is_verified(path: Path, size_bytes: int, expected_sha256: str) -> bool:
    if not path.is_file() or path.stat().st_size != size_bytes:
        return False
    checksum = sha256()
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            checksum.update(chunk)
    return checksum.hexdigest() == expected_sha256


def models_verified() -> bool:
    return all(is_verified(MODEL_DIR / name, *MODEL_MANIFEST[name]) for name in MODEL_FILES)

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
    if models_verified():
        print("Whisper Tiny language ID models already verified")
        return
    archive = tmpdir / "model.tar.bz2"
    download(
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"
        + MODEL_NAME + ".tar.bz2",
        archive,
    )
    MODEL_DIR.mkdir(parents=True, exist_ok=True)
    # Stage the entire bundle beside its final files. Failed extraction never
    # publishes a partial/corrupt model, and each final replacement is atomic.
    with TemporaryDirectory(prefix=".whisper-", dir=MODEL_DIR) as directory:
        staging = Path(directory)
        with tarfile.open(archive, mode="r:bz2") as bundle:
            for name in MODEL_FILES:
                member_path = f"{MODEL_NAME}/{name}"
                members = [m for m in bundle.getmembers() if m.name.removeprefix("./") == member_path]
                if len(members) != 1 or not members[0].isfile():
                    raise RuntimeError(f"Missing or invalid required language-ID model: {name}")
                member = members[0]
                size_bytes, checksum = MODEL_MANIFEST[name]
                if member.size != size_bytes:
                    raise RuntimeError(f"Language-ID size mismatch: {name}")
                source = bundle.extractfile(member)
                if source is None:
                    raise RuntimeError(f"Unable to extract {name}")
                with source, (staging / name).open("wb") as target:
                    shutil.copyfileobj(source, target, length=1024 * 1024)
                if not is_verified(staging / name, size_bytes, checksum):
                    raise RuntimeError(f"Language-ID SHA-256 mismatch: {name}")
        for name in MODEL_FILES:
            (staging / name).replace(MODEL_DIR / name)
    print("Verified Whisper Tiny language ID models bundled (no app download)")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify-only", action="store_true", help="Verify prepared files without downloading")
    if parser.parse_args().verify_only:
        if not AAR_PATH.is_file() or sha256(AAR_PATH.read_bytes()).hexdigest() != AAR_SHA256:
            raise SystemExit("Sherpa-ONNX AAR SHA-256 mismatch or missing file")
        if not models_verified():
            raise SystemExit("Language-ID model integrity verification failed")
        print("Verified language-ID models and Android AAR")
    else:
        with TemporaryDirectory() as tmp:
            tmpdir = Path(tmp)
            ensure_aar(tmpdir)
            ensure_models(tmpdir)
