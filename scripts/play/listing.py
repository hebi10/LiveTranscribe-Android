#!/usr/bin/env python3
"""Validate or submit the reviewed ko-KR store listing and its explicit PNG assets."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import struct
import sys
import zlib

from publisher import HttpTransport, PACKAGE, Publisher, ReleaseError

CATEGORIES = {'icon', 'featureGraphic', 'phoneScreenshots'}
FIELDS = {'title': 30, 'shortDescription': 80, 'fullDescription': 4000}


def png_dimensions(raw):
    if len(raw) < 33 or raw[:8] != b'\x89PNG\r\n\x1a\n':
        raise ReleaseError('Store assets must be complete PNG images')
    position, chunks, compressed = 8, [], bytearray()
    while position < len(raw):
        if position + 12 > len(raw):
            raise ReleaseError('Truncated PNG asset')
        size = struct.unpack_from('>I', raw, position)[0]
        kind = raw[position + 4:position + 8]
        end = position + 12 + size
        if end > len(raw):
            raise ReleaseError('Truncated PNG chunk')
        data = raw[position + 8:end - 4]
        if zlib.crc32(kind + data) & 0xffffffff != struct.unpack_from('>I', raw, end - 4)[0]:
            raise ReleaseError('Corrupt PNG asset checksum')
        chunks.append(kind)
        if kind == b'IHDR':
            if chunks != [b'IHDR'] or size != 13:
                raise ReleaseError('Invalid PNG header')
            width, height, depth, color, compression, filtering, interlace = struct.unpack('>IIBBBBB', data)
            channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}.get(color)
            if not channels or depth not in (1, 2, 4, 8, 16) or compression or filtering or interlace:
                raise ReleaseError('Use a standard noninterlaced PNG asset')
            if not 1 <= width <= 3840 or not 1 <= height <= 3840:
                raise ReleaseError('PNG dimensions exceed supported bounds')
        elif kind == b'IDAT':
            compressed.extend(data)
        elif kind == b'IEND':
            if size or end != len(raw):
                raise ReleaseError('Invalid PNG end chunk')
            break
        position = end
    if not chunks or chunks[0] != b'IHDR' or chunks[-1] != b'IEND' or not compressed:
        raise ReleaseError('Incomplete PNG image')
    stride = (width * channels * depth + 7) // 8 + 1
    expected = height * stride
    decoder = zlib.decompressobj()
    try:
        pixels = decoder.decompress(compressed, expected + 1)
    except zlib.error:
        raise ReleaseError('Invalid PNG pixel data') from None
    if len(pixels) != expected or not decoder.eof or decoder.unused_data or any(pixels[offset] > 4 for offset in range(0, expected, stride)):
        raise ReleaseError('Incomplete PNG pixel rows')
    return width, height


def load_inputs(listing_path, manifest_path):
    text = json.loads(Path(listing_path).read_text(encoding='utf-8'))
    manifest_path = Path(manifest_path).resolve()
    manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
    if text.get('language') != 'ko-KR' or manifest.get('language') != 'ko-KR':
        raise ReleaseError('Only the reviewed ko-KR listing is supported')
    for field, limit in FIELDS.items():
        if not isinstance(text.get(field), str) or not text[field].strip() or len(text[field]) > limit:
            raise ReleaseError(f'Listing {field} must contain 1–{limit} characters')
    images = manifest.get('images', {})
    if set(images) != CATEGORIES:
        raise ReleaseError('Manifest must explicitly contain icon, featureGraphic and phoneScreenshots')
    prepared = {}
    for kind, names in images.items():
        if not isinstance(names, list) or (len(names) != 1 if kind != 'phoneScreenshots' else not 2 <= len(names) <= 8):
            raise ReleaseError('Manifest image counts are invalid')
        prepared[kind] = []
        for name in names:
            if not isinstance(name, str):
                raise ReleaseError('Manifest image names must be relative strings')
            target = (manifest_path.parent / name).resolve()
            if not target.is_relative_to(manifest_path.parent):
                raise ReleaseError('Every asset must remain inside the manifest directory')
            raw = target.read_bytes()
            if len(raw) > 8 * 1024 * 1024:
                raise ReleaseError('Store PNG exceeds the 8 MiB size limit')
            width, height = png_dimensions(raw)
            valid = (width, height) == ((512, 512) if kind == 'icon' else (1024, 500))
            if kind == 'phoneScreenshots':
                valid = 320 <= min(width, height) and max(width, height) <= 3840 and max(width, height) <= 2 * min(width, height)
            if not valid or len(raw) > 8 * 1024 * 1024:
                raise ReleaseError('Store image dimensions or file size are outside the supported Play bounds')
            prepared[kind].append({'data': raw, 'sha256': hashlib.sha256(raw).hexdigest(), 'name': name})
    return {'listing': {key: text[key] for key in ('language', *FIELDS)}, 'images': prepared}


def apply(client, plan, changes_not_sent_for_review=True):
    with client.edit() as edit:
        client.request('PUT', edit + '/listings/ko-KR', plan['listing'])
        for kind, items in plan['images'].items():
            path = edit + '/listings/ko-KR/' + kind
            client.request('DELETE', path)
            for item in items:
                client.request('POST', path + '?uploadType=media', item['data'], 'image/png')
        client.commit(edit, changes_not_sent_for_review)
    try:
        with client.edit() as edit:
            actual = client.request('GET', edit + '/listings/ko-KR')
            if any(actual.get(key) != value for key, value in plan['listing'].items()):
                raise ReleaseError('Listing text mismatch')
            for kind, items in plan['images'].items():
                actual_images = client.request('GET', edit + '/listings/ko-KR/' + kind).get('images', [])
                if [item.get('sha256', '').lower() for item in actual_images] != [item['sha256'] for item in items]:
                    raise ReleaseError('Listing image readback mismatch')
    except ReleaseError:
        raise ReleaseError('Listing edit committed but fresh readback failed; inspect Console before retrying') from None
    return {'package': PACKAGE, 'language': 'ko-KR', 'status': 'api_committed_and_verified',
            'availability': 'requires_console_check', 'changesNotSentForReview': changes_not_sent_for_review,
            'imageCounts': {kind: len(items) for kind, items in plan['images'].items()}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['check', 'apply'])
    parser.add_argument('--listing', type=Path, default=Path('docs/play-store/listing-ko.json'))
    parser.add_argument('--manifest', type=Path, default=Path('assets/play-store/ko-KR/manifest.json'))
    parser.add_argument('--report', type=Path)
    parser.add_argument('--send-for-review', action='store_true')
    args = parser.parse_args()
    try:
        plan = load_inputs(args.listing, args.manifest)
        result = {'package': PACKAGE, 'language': 'ko-KR', 'status': 'validated_not_submitted',
                  'imageCounts': {kind: len(items) for kind, items in plan['images'].items()}}
        if args.mode == 'apply':
            from preflight import require_initialized
            require_initialized(os.environ)
            client = Publisher(HttpTransport(os.environ.get('PLAY_ACCESS_TOKEN', '')))
            result = apply(client, plan, changes_not_sent_for_review=not args.send_for_review)
        if args.report:
            args.report.parent.mkdir(parents=True, exist_ok=True)
            args.report.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print(json.dumps(result, ensure_ascii=False))
        return 0
    except (ReleaseError, OSError, ValueError, KeyError, RuntimeError) as error:
        message = str(error) if isinstance(error, RuntimeError) else 'Listing input or API response is invalid'
        print('Listing failed: ' + message, file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
