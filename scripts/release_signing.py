#!/usr/bin/env python3
"""Use an explicitly provisioned existing release signer; never create a key.

Tool output is captured and discarded on failure so credentials cannot enter logs.
Passwords reach keytool through environment variables, never command arguments.
"""
from __future__ import annotations

import argparse
import base64
import binascii
from hashlib import sha256
import os
from pathlib import Path
import re
import subprocess
import sys

REQUIRED_SECRETS = (
    "ANDROID_RELEASE_KEYSTORE_BASE64",
    "ANDROID_RELEASE_STORE_PASSWORD",
    "ANDROID_RELEASE_KEY_ALIAS",
    "ANDROID_RELEASE_KEY_PASSWORD",
    "ANDROID_RELEASE_CERT_SHA256",
)


class SigningError(RuntimeError):
    pass


def require_environment(environment, names):
    missing = [name for name in names if not environment.get(name, "").strip()]
    if missing:
        raise SigningError("Release signing requires existing signer configuration: " + ", ".join(missing))


def normalize_fingerprint(value):
    fingerprint = re.sub(r"[:\s]", "", value).lower()
    if not re.fullmatch(r"[0-9a-f]{64}", fingerprint):
        raise SigningError("ANDROID_RELEASE_CERT_SHA256 must be a SHA-256 certificate fingerprint")
    return fingerprint


def verify_keystore(keystore, environment, run=subprocess.run):
    require_environment(environment, REQUIRED_SECRETS[1:])
    expected = normalize_fingerprint(environment["ANDROID_RELEASE_CERT_SHA256"])
    if not keystore.is_file():
        raise SigningError("The configured existing release keystore is missing")
    try:
        result = run([
            "keytool", "-exportcert", "-keystore", str(keystore),
            "-alias", environment["ANDROID_RELEASE_KEY_ALIAS"],
            "-storepass:env", "ANDROID_RELEASE_STORE_PASSWORD",
        ], env=dict(environment), capture_output=True, check=False)
    except OSError:
        raise SigningError("Unable to run keytool to verify the existing release certificate") from None
    if result.returncode != 0:
        raise SigningError("Unable to open the existing release keystore and alias; check signing configuration")
    if sha256(result.stdout).hexdigest() != expected:
        raise SigningError("Release keystore certificate does not match ANDROID_RELEASE_CERT_SHA256")


def prepare(keystore, environment, run=subprocess.run):
    require_environment(environment, REQUIRED_SECRETS)
    normalize_fingerprint(environment["ANDROID_RELEASE_CERT_SHA256"])
    try:
        data = base64.b64decode("".join(environment["ANDROID_RELEASE_KEYSTORE_BASE64"].split()), validate=True)
    except (binascii.Error, ValueError):
        raise SigningError("ANDROID_RELEASE_KEYSTORE_BASE64 is not valid base64") from None
    if not data:
        raise SigningError("ANDROID_RELEASE_KEYSTORE_BASE64 contains an empty keystore")
    created = False
    try:
        # Exclusive creation never replaces a real/local key or follows a symlink.
        descriptor = os.open(keystore, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        created = True
        with os.fdopen(descriptor, "wb") as output:
            output.write(data)
        verify_keystore(keystore, environment, run)
    except OSError:
        if created:
            keystore.unlink(missing_ok=True)
        raise SigningError("Cannot create a private temporary release keystore; existing files are never replaced") from None
    except SigningError:
        if created:
            keystore.unlink(missing_ok=True)
        raise


def verify_apk(apk, apksigner, environment, run=subprocess.run):
    require_environment(environment, ("ANDROID_RELEASE_CERT_SHA256",))
    expected = normalize_fingerprint(environment["ANDROID_RELEASE_CERT_SHA256"])
    if not apk.is_file():
        raise SigningError("The signed release APK is missing")
    try:
        result = run([str(apksigner), "verify", "--verbose", "--print-certs", str(apk)],
                     capture_output=True, text=True, check=False)
    except OSError:
        raise SigningError("Unable to run apksigner to verify the release APK") from None
    if result.returncode != 0:
        raise SigningError("apksigner could not verify the release APK")
    fingerprints = re.findall(r"^Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F:]+)\s*$",
                              result.stdout, re.MULTILINE)
    if len(fingerprints) != 1 or normalize_fingerprint(fingerprints[0]) != expected:
        raise SigningError("APK signer certificate does not match the single expected existing release certificate")


def main(arguments=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("prepare", "verify-keystore"):
        commands.add_parser(name).add_argument("--keystore", type=Path, required=True)
    apk_command = commands.add_parser("verify-apk")
    apk_command.add_argument("--apk", type=Path, required=True)
    apk_command.add_argument("--apksigner", type=Path, required=True)
    arguments = parser.parse_args(arguments)
    try:
        if arguments.command == "prepare":
            prepare(arguments.keystore, os.environ)
        elif arguments.command == "verify-keystore":
            verify_keystore(arguments.keystore, os.environ)
        else:
            verify_apk(arguments.apk, arguments.apksigner, os.environ)
    except SigningError as error:
        print(f"Release signing blocked: {error}", file=sys.stderr)
        return 1
    print("Existing release signer certificate verified")
    return 0


if __name__ == "__main__":
    sys.exit(main())
