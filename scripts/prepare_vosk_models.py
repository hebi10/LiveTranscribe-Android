#!/usr/bin/env python3
"""Download Vosk files at *build time*. The installed app never contacts these URLs."""
from __future__ import annotations

from pathlib import Path
from tempfile import TemporaryDirectory
from urllib.request import urlopen
from zipfile import ZipFile
import shutil

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / "app" / "src" / "main" / "assets" / "models"
MODELS = (
    "vosk-model-small-en-us-0.15",
    "vosk-model-small-ja-0.22",
)

def prepare(name: str) -> None:
    target = DEST / name
    if (target / "conf" / "model.conf").is_file():
        print(f"Already prepared: {name}")
        return

    DEST.mkdir(parents=True, exist_ok=True)
    with TemporaryDirectory() as directory:
        archive = Path(directory) / "model.zip"
        url = f"https://alphacephei.com/vosk/models/{name}.zip"
        print(f"Downloading official speech model: {name}", flush=True)
        with urlopen(url, timeout=180) as response, archive.open("wb") as output:
            shutil.copyfileobj(response, output)
        with ZipFile(archive) as bundle:
            for entry in bundle.infolist():
                parts = Path(entry.filename).parts
                if not parts or parts[0] != name or ".." in parts:
                    raise ValueError(f"Unsafe model archive entry: {entry.filename}")
            bundle.extractall(DEST)
    if not (target / "conf" / "model.conf").is_file():
        raise RuntimeError(f"Missing Vosk config in {target}")
    print(f"Ready: {name}")

if __name__ == "__main__":
    for model in MODELS:
        prepare(model)
