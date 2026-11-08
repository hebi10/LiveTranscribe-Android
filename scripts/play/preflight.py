#!/usr/bin/env python3
"""Validate this app's Play bootstrap, authentication and existing AAB signer."""
import argparse
import base64
import binascii
import hashlib
import os
from pathlib import Path
import re
import subprocess
import sys


class PreflightError(RuntimeError):
    pass


def require_initialized(env):
    if env.get('PLAY_CONSOLE_INITIALIZED', '').strip().lower() != 'true':
        raise PreflightError('Registered-binary readiness is not confirmed. Run authenticated inspect --require-binary and pass its verified runtime output before normal API changes. Nothing was deployed.')


def auth_mode(env):
    provider = env.get('PLAY_WORKLOAD_IDENTITY_PROVIDER', '').strip()
    account = env.get('PLAY_SERVICE_ACCOUNT', '').strip()
    if provider or account:
        if not provider or not account:
            raise PreflightError('Both PLAY_WORKLOAD_IDENTITY_PROVIDER and PLAY_SERVICE_ACCOUNT are required for WIF')
        return 'wif'
    if env.get('PLAY_SERVICE_ACCOUNT_JSON', '').strip():
        return 'json'
    raise PreflightError('Play authentication is missing; configure WIF variables or PLAY_SERVICE_ACCOUNT_JSON')


def verify_bundle(path, expected, run=subprocess.run):
    fingerprint = re.sub(r'[:\s]', '', expected).lower()
    if not re.fullmatch(r'[a-f0-9]{64}', fingerprint):
        raise PreflightError('ANDROID_RELEASE_CERT_SHA256 must identify the existing release certificate')
    if not Path(path).is_file():
        raise PreflightError('Signed AAB is missing')
    try:
        verified = run(['jarsigner', '-J-Duser.language=en', '-J-Duser.country=US', '-verify', '-strict', '-verbose', '-certs', str(path)], capture_output=True, check=False)
        # Android app certificates are commonly self-signed (strict exit bit 4).
        # Unsigned entries (16), signer errors (8/32/64), or broken signatures fail.
        if verified.returncode not in (0, 4) or not re.search(rb'jar verified(?:\.|,)', verified.stdout) or b'unsigned entries' in verified.stdout.lower():
            raise PreflightError('AAB cryptographic signature or signed-entry coverage verification failed')
        certificate = run(['keytool', '-printcert', '-jarfile', str(path), '-rfc'], capture_output=True, check=False)
        if certificate.returncode:
            raise PreflightError('Unable to inspect AAB signer certificate')
        matches = re.findall(rb'-----BEGIN CERTIFICATE-----\s*(.*?)\s*-----END CERTIFICATE-----', certificate.stdout, re.S)
        hashes = {hashlib.sha256(base64.b64decode(value, validate=False)).hexdigest() for value in matches}
        if hashes != {fingerprint}:
            raise PreflightError('AAB must have exactly the existing release certificate; other app or replacement keys are forbidden')
    except (OSError, binascii.Error):
        raise PreflightError('Unable to verify existing AAB signer') from None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['bootstrap', 'auth', 'verify-bundle'])
    parser.add_argument('--bundle', type=Path)
    args = parser.parse_args()
    try:
        if args.command == 'bootstrap':
            require_initialized(os.environ)
            print('Console bootstrap explicitly confirmed; installability still requires Console verification')
        elif args.command == 'auth':
            mode = auth_mode(os.environ)
            with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as output:
                output.write(f'auth_mode={mode}\n')
            print('Play authentication configuration present: ' + mode)
        else:
            if not args.bundle:
                raise PreflightError('--bundle is required')
            verify_bundle(args.bundle, os.environ.get('ANDROID_RELEASE_CERT_SHA256', ''))
            print('AAB signature and existing release certificate verified')
        return 0
    except (PreflightError, KeyError, OSError) as error:
        message = str(error) if isinstance(error, PreflightError) else 'Required release environment is missing'
        print('Play preflight blocked: ' + message, file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
