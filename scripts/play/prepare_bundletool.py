#!/usr/bin/env python3
"""Fetch an official, version- and SHA-256-pinned bundletool for static AAB checks."""
import argparse
import hashlib
from pathlib import Path
import sys
import urllib.request

VERSION = '1.18.3'
SHA256 = 'a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29'
URL = f'https://github.com/google/bundletool/releases/download/{VERSION}/bundletool-all-{VERSION}.jar'


def prepare(path):
    path = Path(path)
    if path.is_file() and hashlib.sha256(path.read_bytes()).hexdigest() == SHA256:
        return
    with urllib.request.urlopen(URL, timeout=180) as response:
        data = response.read(64 * 1024 * 1024)
    if hashlib.sha256(data).hexdigest() != SHA256:
        raise RuntimeError('Official bundletool checksum mismatch')
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('path', type=Path)
    try:
        prepare(parser.parse_args().path)
    except (OSError, ValueError, RuntimeError):
        print('Pinned bundletool preparation failed', file=sys.stderr)
        sys.exit(1)
