#!/usr/bin/env python3
"""Validate or update only reviewed ko-KR Google Play phone screenshots.

Never replaces app icon, feature graphic, text, track or any AAB.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import sys

from listing import load_inputs
from publisher import HttpTransport, PACKAGE, Publisher, ReleaseError
from preflight import require_initialized


def prepare(listing_path, manifest_path):
    manifest = json.loads(Path(manifest_path).read_text(encoding='utf-8'))
    plan = load_inputs(listing_path, manifest_path)
    names = manifest['images']['phoneScreenshots']
    if len(names) != 8 or len(set(names)) != 8:
        raise ReleaseError('STORE-01 requires eight unique phone screenshot filenames')
    expected = manifest.get('provenance', {}).get('sha256', {})
    screenshot = plan['images']['phoneScreenshots']
    for item in screenshot:
        if item['sha256'] != expected.get(item['name']):
            raise ReleaseError('STORE-01 pinned screenshot hash mismatch: ' + item['name'])
    return screenshot


def hashes(client, edit, category):
    items = client.request('GET', edit + '/listings/ko-KR/' + category).get('images', [])
    return [item.get('sha256', '').lower() for item in items]


def listing_text(client, edit):
    obj = client.request('GET', edit + '/listings/ko-KR')
    return {key: obj.get(key) for key in ('title', 'shortDescription', 'fullDescription')}


def apply(client, screenshots):
    with client.edit() as edit:
        old_text = listing_text(client, edit)
        icon_hashes = hashes(client, edit, 'icon')
        banner_hashes = hashes(client, edit, 'featureGraphic')
        if not icon_hashes or not banner_hashes or not all(old_text.values()):
            raise ReleaseError('Existing ko-KR listing, icon and feature graphic must exist before STORE-01')
        path = edit + '/listings/ko-KR/phoneScreenshots'
        client.request('DELETE', path)
        for index, item in enumerate(screenshots, 1):
            print('Uploading STORE-01 phone screenshot', index, '/', len(screenshots), flush=True)
            upload = client.request('POST', path + '?uploadType=media', item['data'], 'image/png')
            if not upload.get('image', {}).get('id'):
                raise ReleaseError('Play did not acknowledge screenshot ' + str(index))
        client.request('POST', edit + ':validate', {})
        client.commit(edit, changes_not_sent_for_review=True, fail_if_in_review=True)
    # Independent edit/readback after commit: no additional edits or track actions.
    with client.edit() as verify:
        if hashes(client, verify, 'phoneScreenshots') != [x['sha256'] for x in screenshots]:
            raise ReleaseError('Google Play phone screenshots differ from pinned input SHA-256')
        if hashes(client, verify, 'icon') != icon_hashes:
            raise ReleaseError('Google Play icon unexpectedly changed during screenshot-only edit')
        if hashes(client, verify, 'featureGraphic') != banner_hashes:
            raise ReleaseError('Google Play feature graphic unexpectedly changed during screenshot-only edit')
        if listing_text(client, verify) != old_text:
            raise ReleaseError('Google Play listing text unexpectedly changed during screenshot-only edit')
    return {'package': PACKAGE, 'status': 'screenshot_edit_committed_and_verified',
            'trackChanged': False, 'appIconChanged': False, 'featureGraphicChanged': False,
            'listingTextChanged': False, 'phoneScreenshotCount': len(screenshots),
            'availability': 'requires_console_check'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('check', 'apply'))
    parser.add_argument('--manifest', type=Path, default=Path('assets/play-store/ko-KR/manifest.json'))
    parser.add_argument('--listing', type=Path, default=Path('docs/play-store/listing-ko.json'))
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    try:
        screenshots = prepare(args.listing, args.manifest)
        result = {'package': PACKAGE, 'status': 'validated_not_submitted',
                  'phoneScreenshotCount': len(screenshots)}
        if args.mode == 'apply':
            require_initialized(os.environ)
            result = apply(Publisher(HttpTransport(os.environ.get('PLAY_ACCESS_TOKEN', ''))), screenshots)
        if args.report:
            args.report.parent.mkdir(parents=True, exist_ok=True)
            args.report.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print(json.dumps(result, ensure_ascii=False))
    except (ReleaseError, OSError, ValueError, KeyError) as exc:
        print('STORE-01 screenshots-only validation/upload failed: ' + str(exc), file=sys.stderr)
        raise SystemExit(1)


if __name__ == '__main__':
    main()
