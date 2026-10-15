#!/usr/bin/env python3
"""Download Vosk files at *build time*. The installed app never contacts these URLs."""
from __future__ import annotations

from hashlib import sha256
from pathlib import Path
from tempfile import TemporaryDirectory
from urllib.request import urlopen
from zipfile import ZipFile
import shutil
import argparse

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / "app" / "src" / "main" / "assets" / "models"
MODELS = (
    "vosk-model-small-en-us-0.15",
)
# Exact official https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip,
# downloaded and hashed on 2026-10-15. Verify both the archive and all extracted files.
ARCHIVE_SIZE_BYTES = 41205931
ARCHIVE_SHA256 = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"
MODEL_MANIFEST = {
    "am/final.mdl": (15962575, "75370a0137f9daf8f469dedd7daa4513ae7a621f03240c6e512e2b50b656a7b6"),
    "graph/disambig_tid.int": (102, "9ad87cc166d0998f08f758f47a6223a120dfffcbee805c4849c3aa5e6bb3c0fc"),
    "graph/HCLr.fst": (22416994, "5caafba3081e1646545ac6bff0dd7a318e53dcbdc86f237909ce1d2ac1293d34"),
    "graph/Gr.fst": (24013795, "023c8b7e30704a9e37765c635c252e608a02f361235bf94abdcf2a5225d85b20"),
    "graph/phones/word_boundary.int": (1761, "da199d9c991e0e84681ddbb34627b915b26302d50a8fdaa23c51e2bc3a50b5c3"),
    "conf/model.conf": (290, "8f14cb1eeb07c762c371db648c6be688d347236155ca0f64fb13b6567a8ce81f"),
    "conf/mfcc.conf": (131, "1e2228006d01d805ad1c267fee9f79709ca87ac51bd82b0e3f5c69ba543f0fc4"),
    "ivector/splice.conf": (35, "9f0c5f7c82d18eaf25d8bce470efa9f7741f88411fe428774bc0a9bb69a24756"),
    "ivector/final.dubm": (168048, "8c5d7dd69d2122313baaf19f61f35dd3fa18b70c62ac0687e311e1c46e6daca7"),
    "ivector/global_cmvn.stats": (1080, "33be09afcc80059847a275c3d043b51f1ab954c7c2438ddbbf4745e8ba144ff9"),
    "ivector/final.ie": (8288887, "3f37faf90c375b9e4740b569398b5829ed9cc07d19be6d441f72c3b71d7efcc6"),
    "ivector/online_cmvn.conf": (95, "a2f3571754b64297cb7efb2e7ca3df61995c5a45fcbb97188f90613552bb2dfe"),
    "ivector/final.mat": (44975, "ddd83586dc5f928cda8738b922c85ffe38fc789cb5f9151a712ca12f37265382"),
    "README": (199, "c0cf286e4f7783306c5f6469b37db69228fb16803b03cae661edb2d7bba64ebb"),
}


def is_verified(path: Path, size_bytes: int, expected_sha256: str) -> bool:
    if not path.is_file() or path.stat().st_size != size_bytes:
        return False
    checksum = sha256()
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            checksum.update(chunk)
    return checksum.hexdigest() == expected_sha256


def model_verified(name: str) -> bool:
    if name not in MODELS:
        raise ValueError(f"No pinned manifest for model: {name}")
    return all(is_verified(DEST / name / filename, *metadata)
               for filename, metadata in MODEL_MANIFEST.items())

def prepare(name: str) -> None:
    target = DEST / name
    if model_verified(name):
        print(f"Already verified: {name}")
        return

    DEST.mkdir(parents=True, exist_ok=True)
    with TemporaryDirectory(prefix=f".{name}-", dir=DEST) as directory:
        archive = Path(directory) / "model.zip"
        staging = Path(directory) / "staging"
        url = f"https://alphacephei.com/vosk/models/{name}.zip"
        print(f"Downloading official speech model: {name}", flush=True)
        with urlopen(url, timeout=180) as response, archive.open("wb") as output:
            shutil.copyfileobj(response, output, length=1024 * 1024)
        if archive.stat().st_size != ARCHIVE_SIZE_BYTES:
            raise RuntimeError(f"Vosk archive size mismatch: {name}")
        if not is_verified(archive, ARCHIVE_SIZE_BYTES, ARCHIVE_SHA256):
            raise RuntimeError(f"Vosk archive SHA-256 mismatch: {name}")
        with ZipFile(archive) as bundle:
            for entry in bundle.infolist():
                parts = Path(entry.filename).parts
                if not parts or parts[0] != name or ".." in parts:
                    raise ValueError(f"Unsafe model archive entry: {entry.filename}")
            for filename, (size_bytes, checksum) in MODEL_MANIFEST.items():
                member_path = f"{name}/{filename}"
                entries = [entry for entry in bundle.infolist() if entry.filename == member_path]
                if len(entries) != 1 or entries[0].is_dir():
                    raise RuntimeError(f"Missing or invalid required Vosk file: {filename}")
                if entries[0].file_size != size_bytes:
                    raise RuntimeError(f"Vosk model size mismatch: {filename}")
                prepared = staging / filename
                prepared.parent.mkdir(parents=True, exist_ok=True)
                with bundle.open(entries[0]) as source, prepared.open("wb") as output:
                    shutil.copyfileobj(source, output, length=1024 * 1024)
                if not is_verified(prepared, size_bytes, checksum):
                    raise RuntimeError(f"Vosk model SHA-256 mismatch: {filename}")
        # Only verified complete bundles reach final paths; replacement of each
        # file is atomic on the destination filesystem and failed attempts clean up.
        for filename in MODEL_MANIFEST:
            destination = target / filename
            destination.parent.mkdir(parents=True, exist_ok=True)
            (staging / filename).replace(destination)
    print(f"Verified and ready: {name}")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify-only", action="store_true", help="Verify prepared files without downloading")
    if parser.parse_args().verify_only:
        if not all(model_verified(model) for model in MODELS):
            raise SystemExit("Vosk model integrity verification failed")
        print("Verified English Vosk model files")
    else:
        for model in MODELS:
            prepare(model)

