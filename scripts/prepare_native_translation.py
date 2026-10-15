#!/usr/bin/env python3
"""Prepare the hash-pinned, CPU-only llama.cpp source used by the Android build."""

import argparse
import hashlib
import json
import pathlib
import shutil
import tarfile
import tempfile
import urllib.request

COMMIT = "d81235049384534c167caea52b85a694f6103d14"
ARCHIVE_SHA256 = "6b58785f0a82898f4c3442417ff962e2d4b231b1bee5d033902ad90b27901e14"
ARCHIVE_URL = f"https://codeload.github.com/ggml-org/llama.cpp/tar.gz/{COMMIT}"
REPOSITORY = pathlib.Path(__file__).resolve().parents[1]
DESTINATION = REPOSITORY / "app/src/main/cpp/third_party/llama.cpp"


def source_hashes(directory):
    result = {}
    for path in sorted(directory.rglob("*")):
        if path.is_symlink():
            raise RuntimeError("Unexpected link in prepared native source.")
        if path.is_file() and path.name != ".livecaption-source.json":
            with path.open("rb") as stream:
                result[path.relative_to(directory).as_posix()] = hashlib.file_digest(stream, "sha256").hexdigest()
    return result


def prepare(archive=None, destination=DESTINATION):
    marker = destination / ".livecaption-source.json"
    provenance = {"commit": COMMIT, "archive_sha256": ARCHIVE_SHA256}
    if marker.is_file():
        cached = json.loads(marker.read_text(encoding="utf-8"))
        if all(cached.get(key) == value for key, value in provenance.items()) and cached.get("files") == source_hashes(destination):
            print("Pinned llama.cpp source is ready (prepared file hashes verified).")
            return
    if destination.exists():
        raise RuntimeError("Existing native source is not the pinned version; inspect it before replacing it.")
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="llama-", dir=destination.parent) as temporary:
        staging = pathlib.Path(temporary)
        source = pathlib.Path(archive) if archive else staging / "source.tar.gz"
        if archive is None:
            with urllib.request.urlopen(ARCHIVE_URL, timeout=60) as response, source.open("wb") as output:
                shutil.copyfileobj(response, output)
        with source.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        if digest != ARCHIVE_SHA256:
            raise RuntimeError("Native source archive checksum mismatch.")
        root = f"llama.cpp-{COMMIT}"
        with tarfile.open(source, "r:gz") as bundle:
            for member in bundle.getmembers():
                name = pathlib.PurePosixPath(member.name)
                if name.is_absolute() or ".." in name.parts or name.parts[0] != root:
                    raise RuntimeError("Unexpected path in native source archive.")
                if not (member.isfile() or member.isdir()):
                    raise RuntimeError("Unexpected link or special file in native source archive.")
            bundle.extractall(staging, filter="data")
        extracted = staging / root
        provenance["files"] = source_hashes(extracted)
        (extracted / ".livecaption-source.json").write_text(json.dumps(provenance), encoding="utf-8")
        extracted.rename(destination)
    print(f"Prepared llama.cpp {COMMIT} (archive SHA-256 verified).")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--archive", type=pathlib.Path)
    prepare(parser.parse_args().archive)
