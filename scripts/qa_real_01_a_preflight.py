#!/usr/bin/env python3
"""Read-only LiveTranscribe Android prerequisites. Never proves actual translation."""
import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path

PACKAGE = "com.hebi.livetranslate.debug"
MODEL_BYTES = 2_120_095_795
RESERVE_BYTES = 16 * 1024 * 1024


def pick_device(output):
    devices = [line.split() for line in output.splitlines()[1:] if line.strip()]
    ready = [r[0] for r in devices if len(r) >= 2 and r[1] == "device"]
    if len(devices) != 1 or len(ready) != 1:
        raise ValueError("Connect exactly one authorized Android device.")
    return ready[0]


def parse_storage_kb(output):
    rows = [line.split() for line in output.splitlines() if line.strip()]
    if len(rows) < 2:
        return None
    columns = [col.lower() for col in rows[0]]
    index = next((i for i, col in enumerate(columns) if col in ("available", "avail")), None)
    if index is None:
        return None
    for row in rows[1:]:
        if len(row) > index and row[-1] == "/data" and row[index].isdigit():
            return int(row[index])
    return None


def build_report(properties, free_kb, installed, apk_hash=None):
    sdk_raw = properties.get("ro.build.version.sdk", "")
    sdk = int(sdk_raw) if sdk_raw.isdecimal() else None
    abis = properties.get("ro.product.cpu.abilist", "").split(",")
    arm64 = "arm64-v8a" in abis
    virtual = properties.get("ro.kernel.qemu") == "1" or properties.get("ro.hardware", "").lower() in (
        "ranchu", "goldfish", "vbox86")
    available = free_kb * 1024 if free_kb is not None else None
    required = MODEL_BYTES + RESERVE_BYTES
    sufficient = available >= required if available is not None else None
    return {
        "scope": "QA-REAL-01-A/read-only-prerequisites",
        "package": PACKAGE,
        "androidApi": sdk,
        "arm64Supported": arm64,
        "emulatorDetected": virtual,
        "availableStorageBytes": available,
        "initialModelAndReserveBytes": required,
        "initialModelSpaceSufficient": sufficient,
        "debugAppInstalled": installed,
        "debugApkSha256": apk_hash,
        "candidateReadyForManualQA": sdk is not None and sdk >= 29 and arm64 and not virtual
        and sufficient is True and installed,
        "actualPhysicalDeviceConfirmed": False,
        "offlineModelVerified": None,
        "speechRecognitionVerified": None,
        "firstTranslationVerified": None,
        "secondAiCorrectionVerified": None,
        "playReleaseApproved": False,
        "limitations": "Preflight only. Physical identity, downloaded model SHA and actual translations require direct manual confirmation. Partial downloads can require less free space."
    }


def adb(executable, arguments):
    try:
        result = subprocess.run([executable, *arguments], capture_output=True, text=True,
                                encoding="utf-8", errors="replace", check=False, timeout=20)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise RuntimeError("ADB unavailable or device did not respond.") from exc
    if result.returncode != 0:
        raise RuntimeError("ADB check failed; verify USB debugging and authorization.")
    return result.stdout.strip()


def collect(executable, apk):
    serial = pick_device(adb(executable, ["devices"]))
    def shell(*args):
        return adb(executable, ["-s", serial, "shell", *args])
    props = {key: shell("getprop", key) for key in (
        "ro.build.version.sdk", "ro.product.cpu.abilist", "ro.kernel.qemu", "ro.hardware")}
    storage = parse_storage_kb(shell("df", "-k", "/data"))
    installed = bool(shell("pm", "path", PACKAGE))
    sha = None
    if apk:
        digest = hashlib.sha256()
        with apk.open("rb") as file:
            for block in iter(lambda: file.read(1024 * 1024), b""):
                digest.update(block)
        sha = digest.hexdigest()
    return build_report(props, storage, installed, sha)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        parser.error("Output exists; do not overwrite a previous result.")
    try:
        result = collect(args.adb, args.apk)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with args.output.open("x", encoding="utf-8") as file:
            json.dump(result, file, ensure_ascii=False, indent=2)
            file.write("\n")
        print(json.dumps({"reportCreated": True,
                          "candidateReadyForManualQA": result["candidateReadyForManualQA"],
                          "actualAiInferenceVerified": False}, ensure_ascii=False))
    except (OSError, RuntimeError, ValueError) as exc:
        parser.exit(2, f"QA-REAL-01-A: {exc}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
