#!/usr/bin/env python3
"""Bundle the pinned Silero speech detector; large AI models are prepared in the app."""
from __future__ import annotations

import argparse
from dataclasses import dataclass
from hashlib import sha256
from pathlib import Path
from tempfile import NamedTemporaryFile
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / "app" / "src" / "main" / "assets" / "speech"
CHUNK_BYTES = 1024 * 1024


@dataclass(frozen=True)
class ModelFile:
    filename: str
    size_bytes: int
    sha256: str
    source_url: str


MODEL = ModelFile(
    "silero_vad.onnx",
    643854,
    "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
)


def is_verified(path: Path) -> bool:
    if not path.is_file() or path.stat().st_size != MODEL.size_bytes:
        return False
    checksum = sha256()
    with path.open("rb") as source:
        while chunk := source.read(CHUNK_BYTES):
            checksum.update(chunk)
    return checksum.hexdigest() == MODEL.sha256


def prepare() -> None:
    target = DEST / MODEL.filename
    if is_verified(target):
        print("Silero speech detector already verified")
        return

    DEST.mkdir(parents=True, exist_ok=True)
    temporary_path = None
    try:
        with NamedTemporaryFile(mode="wb", prefix=f".{MODEL.filename}.", suffix=".part",
                                dir=DEST, delete=False) as output:
            temporary_path = Path(output.name)
            checksum = sha256()
            size_bytes = 0
            print("Downloading pinned Silero speech detector", flush=True)
            with urlopen(MODEL.source_url, timeout=180) as response:
                while chunk := response.read(CHUNK_BYTES):
                    size_bytes += len(chunk)
                    if size_bytes > MODEL.size_bytes:
                        raise RuntimeError("Silero speech detector size mismatch")
                    output.write(chunk)
                    checksum.update(chunk)
        if size_bytes != MODEL.size_bytes:
            raise RuntimeError("Silero speech detector size mismatch")
        if checksum.hexdigest() != MODEL.sha256:
            raise RuntimeError("Silero speech detector SHA-256 mismatch")
        temporary_path.replace(target)
        print("Prepared verified Silero speech detector")
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)


def main(arguments: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify-only", action="store_true", help="Verify without downloading")
    if parser.parse_args(arguments).verify_only:
        if not is_verified(DEST / MODEL.filename):
            raise SystemExit("Speech support integrity verification failed: silero_vad.onnx")
        print("Verified Silero speech detector")
    else:
        prepare()


if __name__ == "__main__":
    main()
