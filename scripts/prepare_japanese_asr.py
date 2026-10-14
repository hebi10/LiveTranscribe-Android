#!/usr/bin/env python3
"""Bundle verified Japanese ASR assets at build time; the installed app does not download them."""
from __future__ import annotations

from dataclasses import dataclass
from hashlib import sha256
from pathlib import Path
from tempfile import NamedTemporaryFile
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1]
MODEL_NAME = "reazonspeech-k2-v2"
DEST = ROOT / "app" / "src" / "main" / "assets" / "models" / MODEL_NAME
REPOSITORY = "reazon-research/reazonspeech-k2-v2"
REVISION = "291488c8151be24d7da4bf7af26e533fad96e407"
SILERO_VAD_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
CHUNK_BYTES = 1024 * 1024


@dataclass(frozen=True)
class ModelFile:
    remote_filename: str
    filename: str
    size_bytes: int
    sha256: str
    source_url_override: str | None = None

    @property
    def source_url(self) -> str:
        return self.source_url_override or f"https://huggingface.co/{REPOSITORY}/resolve/{REVISION}/{self.remote_filename}"


# ReazonSpeech provenance: the model author's Hugging Face repository and revision above.
# Sizes and SHA-256 values match the independently verified benchmark model manifest.
# Silero VAD comes from Sherpa-ONNX's official release and is pinned by its verified hash.
MODEL_FILES = (
    ModelFile("encoder-epoch-99-avg-1.int8.onnx", "encoder.int8.onnx", 154670139,
              "2c7bd08a8a99f9ddd0d9e458456577b1f6279214e51426f114f9eced44c54e1d"),
    ModelFile("decoder-epoch-99-avg-1.int8.onnx", "decoder.int8.onnx", 2959337,
              "8f0bff94d38797b03b762634ed03211a8e303d06cc4603cdd0cf4199d6eb1485"),
    ModelFile("joiner-epoch-99-avg-1.int8.onnx", "joiner.int8.onnx", 2696970,
              "49cc7ea1d3d35a40a27442db5e89996da64bf0e683a903dce76e99e57a12e4de"),
    ModelFile("tokens.txt", "tokens.txt", 45754,
              "2c3ac659818a48a0c04010e0593bbc4d7c8a24a054340b01131499c05fd52def"),
    ModelFile("silero_vad.onnx", "silero_vad.onnx", 643854,
              "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
              source_url_override=SILERO_VAD_URL),
)


def is_verified(path: Path, model: ModelFile) -> bool:
    if not path.is_file() or path.stat().st_size != model.size_bytes:
        return False
    checksum = sha256()
    with path.open("rb") as source:
        while chunk := source.read(CHUNK_BYTES):
            checksum.update(chunk)
    return checksum.hexdigest() == model.sha256


def prepare(model: ModelFile) -> None:
    target = DEST / model.filename
    if is_verified(target, model):
        print(f"Already verified: {MODEL_NAME}/{model.filename}")
        return

    DEST.mkdir(parents=True, exist_ok=True)
    temporary_path = None
    try:
        # Keep the temporary file beside its destination so replacement is atomic.
        with NamedTemporaryFile(mode="wb", prefix=f".{model.filename}.", suffix=".part",
                                dir=DEST, delete=False) as output:
            temporary_path = Path(output.name)
            checksum = sha256()
            size_bytes = 0
            print(f"Downloading official Japanese speech model: {model.remote_filename}", flush=True)
            with urlopen(model.source_url, timeout=180) as response:
                while chunk := response.read(CHUNK_BYTES):
                    output.write(chunk)
                    checksum.update(chunk)
                    size_bytes += len(chunk)
                    if size_bytes > model.size_bytes:
                        raise RuntimeError(f"Japanese ASR size mismatch: {model.filename}")
        if size_bytes != model.size_bytes:
            raise RuntimeError(f"Japanese ASR size mismatch: {model.filename}")
        if checksum.hexdigest() != model.sha256:
            raise RuntimeError(f"Japanese ASR SHA-256 mismatch: {model.filename}")
        temporary_path.replace(target)
        print(f"Prepared verified Japanese speech model: {model.filename}")
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)


if __name__ == "__main__":
    for model in MODEL_FILES:
        prepare(model)
