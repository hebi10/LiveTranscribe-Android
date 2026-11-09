#!/usr/bin/env python3
"""Fetch the explicitly approved store-image ZIP from an app-owned, read-only Drive file."""
import argparse
import hashlib
import io
import os
from pathlib import Path, PurePosixPath
import sys
import urllib.error
import urllib.request
import zipfile

DRIVE_FILE_ID = "1FpBQW8VQn_8t2zisdkXPeIEHMYGqeYbD"
EXPECTED_ARCHIVE_SHA256 = "5ab7aad8d9dd7d5c7066078fdad4b0bdb843ae2bc722af54946a44f5c6af75fc"
EXPECTED_FILES = {
    "assets/play-store/ko-KR/manifest.json",
    "assets/play-store/ko-KR/icon.png",
    "assets/play-store/ko-KR/featureGraphic.png",
    *(f"assets/play-store/ko-KR/{name}.png" for name in (
        "01-home", "02-model", "03-language", "04-split",
        "05-popup", "06-popup-controls", "07-history", "08-settings")),
}
MAX_ARCHIVE_BYTES = 8 * 1024 * 1024
MAX_UNCOMPRESSED_BYTES = 15 * 1024 * 1024


def download(token, opener=urllib.request.urlopen):
    if not token or not token.strip():
        raise ValueError("Google Drive read-only token is missing")
    url = f"https://www.googleapis.com/drive/v3/files/{DRIVE_FILE_ID}?alt=media"
    request = urllib.request.Request(url, headers={"Authorization": "Bearer " + token})
    try:
        with opener(request, timeout=90) as response:
            data = response.read(MAX_ARCHIVE_BYTES + 1)
    except (urllib.error.URLError, TimeoutError) as exc:
        raise ValueError("Unable to read the approved Drive ZIP; confirm service account file sharing") from None
    if len(data) > MAX_ARCHIVE_BYTES:
        raise ValueError("Drive ZIP exceeds the approved size bound")
    if hashlib.sha256(data).hexdigest() != EXPECTED_ARCHIVE_SHA256:
        raise ValueError("Approved Drive ZIP digest mismatch; upload rejected")
    return data


def extract(archive, destination):
    destination = Path(destination)
    if destination.exists() and any(destination.iterdir()):
        raise ValueError("Destination must be empty")
    with zipfile.ZipFile(io.BytesIO(archive)) as zipped:
        members = [item for item in zipped.infolist() if not item.is_dir()]
        paths = [item.filename for item in members]
        if len(paths) != len(set(paths)):
            raise ValueError("Duplicate ZIP entries are forbidden")
        selected = set(paths)
        if not EXPECTED_FILES.issubset(selected) or selected - EXPECTED_FILES - {"README_업로드_안내.md", "preview_8screens.png"}:
            raise ValueError("Approved image set or names differ")
        if sum(item.file_size for item in members) > MAX_UNCOMPRESSED_BYTES:
            raise ValueError("Uncompressed images exceed safe size")
        for entry in members:
            relative = PurePosixPath(entry.filename)
            if relative.is_absolute() or ".." in relative.parts or "\\" in entry.filename:
                raise ValueError("Unsafe ZIP path")
            if (entry.external_attr >> 16) & 0o170000 == 0o120000:
                raise ValueError("ZIP symlinks are forbidden")
            target = destination.joinpath(*relative.parts)
            target.parent.mkdir(parents=True, exist_ok=True)
            with zipped.open(entry) as source, target.open("xb") as output:
                output.write(source.read())
    return destination / "assets/play-store/ko-KR/manifest.json"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", default="output/approved-play-images")
    args = parser.parse_args()
    try:
        archive = download(os.environ.get("PLAY_DRIVE_ACCESS_TOKEN", ""))
        path = extract(archive, args.output_dir)
        # Defer format, source and per-image verification to the existing Play listing validator.
        sys.path.insert(0, str(Path(__file__).resolve().parent))
        from listing import load_inputs
        payload = load_inputs(Path("docs/play-store/listing-ko.json"), path)
        print(f"Validated approved Drive archive: {len(payload['images']['phoneScreenshots'])} genuine-screen composites")
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as result:
            result.write(f"manifest={path}\n")
        return 0
    except (ValueError, OSError, zipfile.BadZipFile, KeyError, RuntimeError) as exc:
        print("Approved listing image preparation failed: " + str(exc), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
