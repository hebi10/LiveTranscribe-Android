#!/usr/bin/env python3
"""Check the actual Play-installed release's prerequisites without modifying the device."""
import argparse
import json
import re
from pathlib import Path
from qa_real_01_a_preflight import adb, pick_device, parse_storage_kb, build_report

PACKAGE = "com.hebi.livetranslate"
PLAY_INSTALLER = "com.android.vending"


def package_metadata(output):
    rows = [line.split() for line in output.splitlines() if line.strip()]
    exact = [row for row in rows if row[0] == "package:" + PACKAGE]
    if len(exact) != 1:
        return None, None
    versions = [value.split(":", 1)[1] for value in exact[0][1:]
                if value.startswith("versionCode:")]
    installers = [value.split("=", 1)[1] for value in exact[0][1:]
                  if value.startswith("installer=")]
    version = int(versions[0]) if len(versions) == 1 and re.fullmatch(r"[1-9][0-9]{0,9}", versions[0]) else None
    installer = installers[0] if len(installers) == 1 else None
    return version, installer


def release_report(properties, free_kb, metadata, expected_code, expected_sha):
    installed_code, installer = package_metadata(metadata)
    base = build_report(properties, free_kb, installed_code is not None)
    candidate = (base["androidApi"] is not None and base["androidApi"] >= 29
                 and base["arm64Supported"] and not base["emulatorDetected"]
                 and installed_code == expected_code and installer == PLAY_INSTALLER)
    return {
        "schema": "qa-real-play-preflight-v1",
        "package": PACKAGE,
        "expectedSourceSha": expected_sha,
        "sourceShaIndependentlyVerified": False,
        "expectedVersionCode": expected_code,
        "installedVersionCode": installed_code,
        "installerIsGooglePlay": installer == PLAY_INSTALLER,
        "androidApi": base["androidApi"],
        "arm64Supported": base["arm64Supported"],
        "emulatorDetected": base["emulatorDetected"],
        "availableStorageBytes": base["availableStorageBytes"],
        "initialModelAndReserveBytes": base["initialModelAndReserveBytes"],
        "initialModelSpaceSufficient": base["initialModelSpaceSufficient"],
        "candidateReadyForManualQA": candidate,
        "actualPhysicalDeviceConfirmed": False,
        "offlineModelVerified": None,
        "actualAiInferenceVerified": False,
        "purchaseVerified": False,
        "playReleaseApproved": False,
        "limitations": "Compare versionCode and source with the actual release receipt. Installer metadata is not Play Integrity attestation. Check existing model state and actual hardware manually. Low free storage can be normal for already verified models.",
    }


def collect(executable, expected_code, expected_sha):
    serial = pick_device(adb(executable, ["devices"]))
    def shell(*args):
        return adb(executable, ["-s", serial, "shell", *args])
    properties = {key: shell("getprop", key) for key in (
        "ro.build.version.sdk", "ro.product.cpu.abilist", "ro.kernel.qemu", "ro.hardware")}
    storage = parse_storage_kb(shell("df", "-k", "/data"))
    metadata = shell("pm", "list", "packages", "-i", "--show-versioncode", PACKAGE)
    return release_report(properties, storage, metadata, expected_code, expected_sha)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--expected-version-code", required=True, type=int)
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if not 1 <= args.expected_version_code <= 2100000000 or not re.fullmatch(r"[a-f0-9]{40}", args.source_sha):
        parser.error("Supply the actual internal release receipt's versionCode and source SHA")
    if args.output.exists():
        parser.error("Output exists; do not overwrite prior evidence")
    try:
        result = collect(args.adb, args.expected_version_code, args.source_sha)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with args.output.open("x", encoding="utf-8") as file:
            json.dump(result, file, ensure_ascii=False, indent=2)
            file.write("\n")
        print(json.dumps({"reportCreated": True,
                          "candidateReadyForManualQA": result["candidateReadyForManualQA"],
                          "actualAiInferenceVerified": False}, ensure_ascii=False))
    except (OSError, RuntimeError, ValueError):
        parser.exit(2, "Play preflight failed; check ADB, device authorization and output path.\n")
    return 0 if result["candidateReadyForManualQA"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
