#!/usr/bin/env python3
"""Pin and stage the verified 2026-11-11 Korean Play phone screenshots.

Retain the previously approved 2026-11-09 launcher icon and feature graphic.
Do not upload, modify listing text, or change a release track here.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import tempfile
import zipfile

from drive_assets import extract
from listing import load_inputs, png_dimensions

BASE_ARCHIVE = Path('assets/play-store/approved/LiveTranscribe_10_assets_20261109.zip')
NEW_ARCHIVE = Path('assets/play-store/approved/LiveTranscribe_Store_8screens_20261111.zip')
BASE_SHA256 = '5ab7aad8d9dd7d5c7066078fdad4b0bdb843ae2bc722af54946a44f5c6af75fc'
NEW_SHA256 = '8a5a5627feceb4d900460a4bd0bd37f5d8c12ed7a42486042da855e12dc0e5b2'
NEW_SHAS = (
    '3e2e34c2e27f0d730d14972ed4cb06021091c8de57b7d76d67a2165ec0387232',
    'd7a9b6d0776392630a050c8b3ffd3111da60ac4442025f03cf2e1f02429fd38a',
    'eb022344c84c4717a8bd68198d74ac9ad013c6d5cdfea64c24c009c69bb3e60d',
    '989dbe1e2550eac744e59ab82439c16609698a1cf4377c0d7b1f755dfca20f7b',
    '1f45f07c2e1d8778fb0d909f9ad4d393a1a0f42f12c4ffa876a74abc416720cb',
    'b0aeaec637480853dc74ea5257c9e5f920acff4386d6eaae7804c1477e947f80',
    '8fab2bed1eee216b4a06422a682b18da81a5706dae4ecfc895901dbc40825ce4',
    'a408acd3d22e4c1af077e88d1baff3003b6850772b1e838c8e4ba95302cd2444',
)


def _read_pinned(path, expected, max_bytes):
    data = path.read_bytes()
    if len(data) > max_bytes or hashlib.sha256(data).hexdigest() != expected:
        raise ValueError('Pinned Play image archive changed: ' + str(path))
    return data


def overlay(manifest_path):
    manifest_path = Path(manifest_path)
    archive = _read_pinned(NEW_ARCHIVE, NEW_SHA256, 5 * 1024 * 1024)
    with zipfile.ZipFile(io.BytesIO(archive)) as z:
        names = z.namelist()
        if len(names) != 9 or len(set(names)) != 9 or 'release_manifest_8screens.json' not in names:
            raise ValueError('Unexpected 2026-11-11 Play screenshot bundle members')
        release = json.loads(z.read('release_manifest_8screens.json'))
        items = release.get('screenshots')
        if (release.get('language') != 'ko-KR' or release.get('date') != '2026-11-11'
                or not isinstance(items, list) or len(items) != 8):
            raise ValueError('Unexpected Play screenshot release manifest')
        filenames = [item.get('filename') for item in items]
        if set(names) != set(filenames) | {'release_manifest_8screens.json'}:
            raise ValueError('Screenshot archive contents and manifest differ')
        prepared_names = []
        for number, item in enumerate(items, 1):
            filename = item['filename']
            if (item.get('sequence') != number or item.get('sha256') != NEW_SHAS[number - 1]
                    or not isinstance(filename, str) or '/' in filename or '\\' in filename
                    or not filename.lower().endswith('.png')):
                raise ValueError('Unapproved Play screenshot entry')
            data = z.read(filename)
            if (len(data) > 2 * 1024 * 1024 or hashlib.sha256(data).hexdigest() != NEW_SHAS[number - 1]
                    or png_dimensions(data) != (1080, 1920) or data[24:26] != b'\x08\x02'):
                raise ValueError('Invalid screenshot bytes/dimensions/format')
            dest_name = f'{number:02d}-20261111.png'
            (manifest_path.parent / dest_name).write_bytes(data)
            prepared_names.append(dest_name)
    base = json.loads(manifest_path.read_text(encoding='utf-8'))
    if base.get('language') != 'ko-KR' or set(base.get('images', {})) != {'icon', 'featureGraphic', 'phoneScreenshots'}:
        raise ValueError('Original Play manifest does not match expected categories')
    old_sha = base.get('provenance', {}).get('sha256', {})
    for kind in ('icon', 'featureGraphic'):
        for name in base['images'][kind]:
            raw = (manifest_path.parent / name).read_bytes()
            if hashlib.sha256(raw).hexdigest() != old_sha.get(name):
                raise ValueError('Existing approved icon or feature graphic changed')
    base['images']['phoneScreenshots'] = prepared_names
    base['provenance'] = {
        'iconAndFeature': 'Retained 2026-11-09 approved, SHA-pinned Play artwork',
        'screenshots': 'Real Android 35 emulator idle and setup UI, edited only outside the captured screen',
        'sourceArchiveSha256': NEW_SHA256,
        'originalArchiveSha256': BASE_SHA256,
        'scope': 'No actual 2.12GB model inference, audio capture or translated speech verified',
        'sha256': {**{name: old_sha[name] for kind in ('icon', 'featureGraphic') for name in base['images'][kind]},
                   **dict(zip(prepared_names, NEW_SHAS))},
    }
    manifest_path.write_text(json.dumps(base, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    images = load_inputs(Path('docs/play-store/listing-ko.json'), manifest_path)['images']
    if {k: len(v) for k, v in images.items()} != {'icon': 1, 'featureGraphic': 1, 'phoneScreenshots': 8}:
        raise ValueError('Unexpected Play image category count')
    for image, digest in zip(images['phoneScreenshots'], NEW_SHAS):
        if image['sha256'] != digest:
            raise ValueError('Validated screenshot digest differs')
    print('Validated 2026-11-11 Play screenshot set (8/8); prior icon and graphic unchanged.')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--manifest', type=Path, help='Manifest from the verified previous Play archive')
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    if args.check == bool(args.manifest):
        parser.error('Choose exactly one of --check or --manifest')
    _read_pinned(BASE_ARCHIVE, BASE_SHA256, 8 * 1024 * 1024)
    if args.check:
        with tempfile.TemporaryDirectory() as td:
            path = extract(BASE_ARCHIVE.read_bytes(), Path(td) / 'approved')
            overlay(path)
    else:
        overlay(args.manifest)


if __name__ == '__main__':
    main()
