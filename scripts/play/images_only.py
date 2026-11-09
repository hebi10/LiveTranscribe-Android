#!/usr/bin/env python3
"""Update only the three approved Korean image categories in an existing Play listing."""
import argparse
import json
import os
from pathlib import Path
import sys

from listing import load_inputs
from publisher import HttpTransport, PACKAGE, Publisher, ReleaseError, PlayApiError
from preflight import require_initialized

CATEGORIES = ("icon", "featureGraphic", "phoneScreenshots")


def apply_images(client, images):
    stage = "edit creation"
    try:
        with client.edit() as edit:
            for kind in CATEGORIES:
                endpoint = edit + "/listings/ko-KR/" + kind
                stage = kind + " delete existing images"
                print("Play image step: " + stage, flush=True)
                client.request("DELETE", endpoint)
                for index, item in enumerate(images[kind], 1):
                    stage = kind + " upload " + str(index) + "/" + str(len(images[kind]))
                    print("Play image step: " + stage, flush=True)
                    response = client.request("POST", endpoint + "?uploadType=media", item["data"], "image/png")
                    if not response.get("image", {}).get("id"):
                        raise ReleaseError("Play did not confirm uploaded " + kind + " image")
            stage = "edit validate"
            print("Play image step: " + stage, flush=True)
            client.request("POST", edit + ":validate", {})
            stage = "edit commit"
            print("Play image step: " + stage, flush=True)
            client.commit(edit, changes_not_sent_for_review=True)
    except PlayApiError as error:
        status = error.api_status or "API_STATUS_UNAVAILABLE"
        reasons = ",".join(error.diagnostic_reasons) or "NO_STRUCTURED_REASON"
        raise ReleaseError(
            f"Play image stage '{stage}' denied: HTTP {error.http_status}, status {status}, reason {reasons}. "
            "Inspect the app-specific Play permissions and draft app state before retrying."
        ) from None
    try:
        with client.edit() as readback:
            for kind in CATEGORIES:
                stage = kind + " independent readback"
                current = client.request("GET", readback + "/listings/ko-KR/" + kind).get("images", [])
                expected = [item["sha256"] for item in images[kind]]
                if [item.get("sha256", "").lower() for item in current] != expected:
                    raise ReleaseError("Play image hashes differ after commit: " + kind)
    except ReleaseError:
        raise ReleaseError("Image edit committed, but independent readback failed; inspect Play Console before retrying") from None
    return {
        "package": PACKAGE, "language": "ko-KR", "status": "image_edit_committed_and_verified",
        "changesNotSentForReview": True,
        "imageCounts": {kind: len(images[kind]) for kind in CATEGORIES},
        "textChanged": False, "trackChanged": False
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    try:
        require_initialized(os.environ)
        images = load_inputs(Path("docs/play-store/listing-ko.json"), args.manifest)["images"]
        result = apply_images(Publisher(HttpTransport(os.environ.get("PLAY_ACCESS_TOKEN", ""))), images)
        if args.report:
            args.report.parent.mkdir(parents=True, exist_ok=True)
            args.report.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(result, ensure_ascii=False))
    except (ReleaseError, RuntimeError, OSError, ValueError, KeyError) as exc:
        print("Image-only Play update failed: " + str(exc), file=sys.stderr)
        raise SystemExit(1)


if __name__ == "__main__":
    main()
