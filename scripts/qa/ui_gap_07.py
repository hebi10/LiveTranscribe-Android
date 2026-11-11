#!/usr/bin/env python3
"""UI-GAP-07: declare emulator variants and verify capture provenance."""
import argparse
import hashlib
import json
import re
import struct
from pathlib import Path

VARIANTS = {
    "phone-light": (1080, 1920, 420, 1.0, "light"),
    "phone-dark": (1080, 1920, 420, 1.0, "dark"),
    "compact-light160": (840, 1480, 420, 1.6, "light"),
    "compact-dark160": (840, 1480, 420, 1.6, "dark"),
    "landscape-light": (1920, 1080, 420, 1.0, "light"),
    "landscape-dark": (1920, 1080, 420, 1.0, "dark"),
    "tablet-light": (1600, 2560, 320, 1.0, "light"),
    "tablet-dark": (1600, 2560, 320, 1.0, "dark"),
    "fold-cover-light": (904, 2268, 420, 1.0, "light"),
    "fold-expanded-dark": (1768, 2208, 360, 1.0, "dark"),
}
SCREENS = ("home", "settings", "history", "caption-preview")
SCOPE = "Real emulator idle UI only; no physical fold, model install, speech recognition or translation."


def names_from(raw):
    names = raw.split()
    if not names or len(set(names)) != len(names):
        raise ValueError("Expected one or more unique QA variants")
    if any(name not in VARIANTS for name in names):
        raise ValueError("Unrecognized QA variant")
    return names


def verify(folder, api, revision, variants):
    if api not in (29, 35, 36):
        raise ValueError("API level outside required QA matrix")
    if not re.fullmatch(r"[a-f0-9]{40}", revision):
        raise ValueError("GitHub SHA is not a full verified revision")
    captures = []
    sha_values = set()
    for name in variants:
        width, height, density, font, theme = VARIANTS[name]
        manifest = json.loads((folder / f"qa-manifest-{name}.json").read_text(encoding="utf-8"))
        expected_names = [f"qa-07-{name}-{screen}.png" for screen in SCREENS]
        required = {
            "variant": name, "sourceRevision": revision,
            "applicationId": "com.hebi.livetranslate.debug", "apiLevel": api,
            "width": width, "height": height, "densityDpi": density,
            "nightMode": theme, "screenshots": expected_names,
            "simulatedFoldSizeOnly": name.startswith("fold-"),
            "physicalFoldVerified": False, "offlineInferenceVerified": False,
            "scope": SCOPE,
        }
        for key, value in required.items():
            if manifest.get(key) != value:
                raise ValueError(f"{name}: invalid {key}: {manifest.get(key)}")
        if abs(float(manifest["fontScale"]) - font) > 0.08:
            raise ValueError(f"{name}: wrong Android font scale")
        for file_name in expected_names:
            payload = (folder / file_name).read_bytes()
            if len(payload) < 10000 or payload[:8] != b"\x89PNG\r\n\x1a\n":
                raise ValueError(f"{file_name}: missing or malformed real PNG")
            if struct.unpack(">II", payload[16:24]) != (width, height):
                raise ValueError(f"{file_name}: screenshot dimensions differ from requested device")
            digest = hashlib.sha256(payload).hexdigest()
            if digest in sha_values:
                raise ValueError(f"{file_name}: duplicate screenshot")
            sha_values.add(digest)
            captures.append({
                "name": file_name, "variant": name, "apiLevel": api,
                "theme": theme, "fontScale": font, "width": width, "height": height,
                "bytes": len(payload), "sha256": digest,
            })
    evidence = {
        "sourceRevision": revision,
        "apiLevel": api,
        "applicationId": "com.hebi.livetranslate.debug",
        "status": "automated screenshot provenance passed; manual visual QA remains separate",
        "scenarioCount": len(variants),
        "screenshotCount": len(captures),
        "screenshots": captures,
        "notVerified": [
            "real AI model installation and speech-to-Korean translation",
            "physical foldable posture, hinge and orientation continuity",
            "real overlay drag, resize and app-switch persistence",
            "actual transcript storage, search and deletion",
            "physical-device touch and accessibility audit",
        ],
    }
    output = folder / f"ui-gap-07-final-api-{api}-evidence.json"
    output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Validated {len(captures)} nonduplicate Android PNGs for API {api} ({len(variants)} scenarios).")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("scenarios", "verify", "selftest"))
    parser.add_argument("--names", default="")
    parser.add_argument("--folder", type=Path, default=Path("qa-captures"))
    parser.add_argument("--api", type=int)
    parser.add_argument("--revision")
    args = parser.parse_args()
    if args.command == "selftest":
        assert len(VARIANTS) == 10
        assert all(variant[0] > 0 and variant[1] > 0 for variant in VARIANTS.values())
        assert set(SCREENS) == {"home", "settings", "history", "caption-preview"}
        print("UI-GAP-07 QA variant contract passed")
        return
    variants = names_from(args.names)
    if args.command == "scenarios":
        for name in variants:
            width, height, density, font, theme = VARIANTS[name]
            print(name, f"{width}x{height}", density, font,
                  "yes" if theme == "dark" else "no")
        return
    if args.api is None or args.revision is None:
        parser.error("verify requires --api and --revision")
    verify(args.folder, args.api, args.revision, variants)


if __name__ == "__main__":
    main()
