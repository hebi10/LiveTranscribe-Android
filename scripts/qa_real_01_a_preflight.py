#!/usr/bin/env python3
"""Read-only ARM64 device / local model integrity preflight.

Nothing is installed, permissions are never changed, and audio/text is never read.
"""
import argparse
import hashlib
import json
import re
import shutil
import subprocess
from pathlib import Path

PACKAGE = "com.hebi.livetranslate.debug"
CATALOG = Path(__file__).resolve().parents[1] / "app/src/main/java/com/charles/livecaptionn/models/OfflineModelCatalog.kt"
ENTRY = re.compile(
    r'OfflineModelFile\(\s*"(?P<id>[a-zA-Z0-9_]+)"\s*,\s*"(?P<path>[a-zA-Z0-9._/-]+)"\s*,\s*'
    r'"https://[^"\s]+"\s*,\s*(?P<size>\d+)L\s*,\s*"(?P<sha>[a-f0-9]{64})"\s*\)',
    re.MULTILINE,
)


class PreflightError(Exception):
    pass


def parse_catalog(text):
    models = []
    for match in ENTRY.finditer(text):
        spec = match.groupdict()
        if ".." in spec["path"].split("/") or spec["path"].startswith("/"):
            raise PreflightError("Invalid model path in pinned catalog")
        spec["size"] = int(spec["size"])
        models.append(spec)
    if len(models) != 7 or len({m["id"] for m in models}) != 7:
        raise PreflightError("Current offline model catalog does not match the seven expected entries")
    return models


def apk_digest(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def adb(arguments, serial=None, timeout=30):
    command = ["adb"] + (["-s", serial] if serial else []) + arguments
    try:
        response = subprocess.run(command, text=True, encoding="utf-8", errors="replace",
                                  capture_output=True, check=False, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise PreflightError("ADB command failed or timed out") from exc
    return response.returncode, response.stdout.strip(), response.stderr.strip()


def select_device(serial=None):
    if not shutil.which("adb"):
        raise PreflightError("Android Platform Tools (adb) is not installed")
    code, output, _ = adb(["devices"])
    if code != 0:
        raise PreflightError("Cannot enumerate ADB devices")
    authorized = [row.split()[0] for row in output.splitlines()[1:]
                  if len(row.split()) >= 2 and row.split()[1] == "device"]
    if serial:
        if serial not in authorized:
            raise PreflightError("Specified ADB device is not authorized")
        return serial
    if len(authorized) != 1:
        raise PreflightError("Connect and authorize exactly one device (or pass --serial)")
    return authorized[0]


def read_property(name, serial):
    code, output, _ = adb(["shell", "getprop", name], serial)
    if code != 0:
        raise PreflightError("Unable to read device environment")
    return output


def read_model_integrity(spec, serial):
    path = "no_backup/ai-models/" + spec["path"]
    base = ["shell", "run-as", PACKAGE, "toybox"]
    code, output, _ = adb(base + ["stat", "-c", "%s", path], serial, timeout=30)
    if code != 0 or not output.isdecimal():
        return {"modelId": spec["id"], "status": "BLOCKED", "reason": "file missing or debug run-as unavailable"}
    if int(output) != spec["size"]:
        return {"modelId": spec["id"], "status": "FAIL", "reason": "size differs from pinned catalog"}
    code, output, _ = adb(base + ["sha256sum", path], serial, timeout=240)
    if code != 0 or not re.match(r"^[0-9a-f]{64}(?:\s|$)", output):
        return {"modelId": spec["id"], "status": "BLOCKED", "reason": "hash read unavailable"}
    actual_hash = output.split()[0]
    return {"modelId": spec["id"], "status": "PASS" if actual_hash == spec["sha"] else "FAIL",
            "sizeBytes": spec["size"], "sha256Match": actual_hash == spec["sha"]}


def inspect_device(serial, models, check_models):
    abi = read_property("ro.product.cpu.abilist", serial).split(",")
    api_string = read_property("ro.build.version.sdk", serial)
    qemu = read_property("ro.kernel.qemu", serial)
    hardware = read_property("ro.hardware", serial).lower()
    is_emulator = qemu == "1" or hardware in ("ranchu", "goldfish")
    try:
        api = int(api_string)
    except ValueError as exc:
        raise PreflightError("Invalid Android API number") from exc
    code, output, _ = adb(["shell", "pm", "path", PACKAGE], serial)
    installed = code == 0 and output.startswith("package:")
    compatible = api >= 29 and "arm64-v8a" in abi and not is_emulator
    data = {
        "androidApi": api,
        "arm64Supported": "arm64-v8a" in abi,
        "emulatorDetected": is_emulator,
        "testPackageInstalled": installed,
        "physicalArm64Eligible": compatible,
        "modelCatalogCount": len(models),
        "modelCatalogBytes": sum(m["size"] for m in models),
        "modelIntegrity": "NOT RUN",
        "models": [],
        "microphoneTranslation": "NOT RUN",
        "videoAudioTranslation": "NOT RUN",
        "secondAiCorrection": "NOT RUN",
        "permissionsChanged": False,
        "deviceIdentifierStored": False,
    }
    if check_models and compatible and installed:
        data["models"] = [read_model_integrity(model, serial) for model in models]
        data["modelIntegrity"] = ("PASS" if all(m["status"] == "PASS" for m in data["models"])
                                  else "BLOCKED" if any(m["status"] == "BLOCKED" for m in data["models"])
                                  else "FAIL")
    if not compatible or not installed:
        data["blocker"] = ("Requires a physical ARM64 Android 10+ device" if not compatible
                           else "Install the separate debug APK with explicit user consent")
    return data


def save_report(path, report):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x", encoding="utf-8", newline="\n") as handle:
        json.dump(report, handle, ensure_ascii=False, indent=2)
        handle.write("\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="ADB selection; never saved to report")
    parser.add_argument("--apk", type=Path, help="Optional local test-only APK for SHA-256 check")
    parser.add_argument("--expected-apk-sha256", help="Expected 64-digit lower-case digest")
    parser.add_argument("--verify-models", action="store_true", help="Explicitly hash all 7 app-private offline models (slow)")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        models = parse_catalog(CATALOG.read_text(encoding="utf-8"))
        result = {"qaStage": "QA-REAL-01-A", "physicalDeviceTestExecuted": False,
                  "translationEvidence": "NOT RUN", "playDeployment": "NOT REQUESTED"}
        if args.expected_apk_sha256:
            if not args.apk or not re.fullmatch("[a-f0-9]{64}", args.expected_apk_sha256):
                raise PreflightError("An APK and a full lowercase SHA-256 are required")
        if args.apk:
            if not args.apk.is_file():
                raise PreflightError("Specified test APK does not exist")
            sha = apk_digest(args.apk)
            result["apkSha256"] = sha
            result["apkDigestMatches"] = sha == args.expected_apk_sha256 if args.expected_apk_sha256 else None
            if args.expected_apk_sha256 and not result["apkDigestMatches"]:
                raise PreflightError("Test APK differs from the verified GitHub Actions artifact")
        device = select_device(args.serial)
        result.update(inspect_device(device, models, args.verify_models))
        result["physicalDeviceTestExecuted"] = result["physicalArm64Eligible"]
        save_report(args.output, result)
        print(f"Written: {args.output} (no serial/audio/caption content is included)")
        print(f"Device eligibility: {result['physicalArm64Eligible']}; model integrity: {result['modelIntegrity']}")
    except (PreflightError, ValueError, OSError, FileExistsError) as exc:
        parser.exit(2, f"QA-REAL-01-A: {exc}\n")


if __name__ == "__main__":
    main()
