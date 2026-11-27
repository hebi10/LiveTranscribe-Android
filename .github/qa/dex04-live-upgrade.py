"""Verify an in-place release update using only the disposable test emulator."""
import hashlib
import json
import re
import subprocess
import sys
import time
from pathlib import Path
from xml.etree import ElementTree as ET


APP = 'com.hebi.livetranslate'
MAIN = f'{APP}/com.charles.livecaptionn.MainActivity'
HOME = '실시간 영상 번역'
PACKAGE_ROOT = f'/data/user/0/{APP}/files'
GLOSSARY = f'{PACKAGE_ROOT}/caption_glossary.json'
SETTINGS = f'{PACKAGE_ROOT}/datastore/caption_settings.preferences_pb'
PHRASE = 'DEXQaWord'
REPLACEMENT = 'DEXQaStoredValue'


def adb(*args, binary=False, timeout=90):
    child = subprocess.run(['adb', *args], check=True, capture_output=True, timeout=timeout)
    if binary:
        return child.stdout
    return child.stdout.decode(errors='replace')


def launch():
    result = adb('shell', 'am', 'start', '-W', '-n', MAIN)
    assert 'Status: ok' in result, result


def reopen():
    adb('shell', 'am', 'force-stop', APP)
    launch()


def xml():
    device_path = '/sdcard/dex04-live.xml'
    adb('shell', 'uiautomator', 'dump', device_path)
    return adb('exec-out', 'cat', device_path)


def evidence(output, label):
    (output / f'{label}.png').write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
    (output / f'{label}.xml').write_text(xml(), encoding='utf-8')


def position(node):
    points = [int(value) for value in re.findall(r'\d+', node.get('bounds', ''))]
    if len(points) == 4 and points[0] < points[2] and points[1] < points[3]:
        return ((points[0] + points[2]) // 2, (points[1] + points[3]) // 2)
    return None


def label(text):
    return lambda node: node.get('text') == text or node.get('content-desc') == text


def wait_for(predicate, description, *, scroll=False, attempts=12):
    dimensions = adb('shell', 'wm', 'size')
    width, height = map(int, re.findall(r'(\d+)x(\d+)', dimensions)[-1])
    for attempt in range(attempts):
        try:
            nodes = list(ET.fromstring(xml()).iter('node'))
        except (ET.ParseError, subprocess.CalledProcessError):
            time.sleep(1)
            continue
        for node in nodes:
            if predicate(node) and (center := position(node)):
                return center
        if scroll and attempt > 0:
            adb('shell', 'input', 'swipe', str(width * 9 // 10), str(height * 3 // 4),
                str(width * 9 // 10), str(height // 3), '325')
        time.sleep(1)
    raise AssertionError(f'Visible Android view not found: {description}')


def tap(point):
    adb('shell', 'input', 'tap', *map(str, point))


def tap_label(text, **options):
    tap(wait_for(label(text), text, **options))


def read_root_file(path):
    return adb('exec-out', 'cat', path, binary=True)


def version_code():
    result = adb('shell', 'dumpsys', 'package', APP)
    return int(re.search(r'\bversionCode=(\d+)', result).group(1))


def assert_saved(output, phase):
    glossary = read_root_file(GLOSSARY)
    settings = read_root_file(SETTINGS)
    assert PHRASE.encode() in glossary and REPLACEMENT.encode() in glossary
    assert b'MIC' in settings, 'UI-selected microphone input was not saved'
    report = dict(
        glossary_sha256=hashlib.sha256(glossary).hexdigest(),
        settings_sha256=hashlib.sha256(settings).hexdigest(),
        glossary_test_value=REPLACEMENT,
        version_code=version_code(),
    )
    (output / f'{phase}.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    return report


def run(baseline, optimized, output):
    assert any(adb('shell', 'getprop', key).strip() == '1'
               for key in ('ro.kernel.qemu', 'ro.boot.qemu')), 'Disposable emulator is required'
    assert 'arm64-v8a' in adb('shell', 'getprop', 'ro.product.cpu.abilist').strip().split(',')
    adb('root')
    adb('wait-for-device')
    assert adb('shell', 'id', '-u').strip() == '0', 'Emulator root is needed for comparing test-only app data'
    adb('shell', 'svc', 'wifi', 'disable')
    adb('shell', 'svc', 'data', 'disable')
    assert 'Success' in adb('install', baseline)
    assert version_code() == 32
    launch()
    wait_for(label(HOME), HOME)
    tap_label('음성 캡처 권한 허용', scroll=True)
    tap(wait_for(lambda node: node.get('resource-id', '').endswith('permission_allow_foreground_only_button'),
                 'first Android microphone permission dialog'))
    tap_label('마이크', scroll=True)
    tap_label('사용자 용어집 · 자막 프리셋', scroll=True)
    tap_label('용어 추가')
    tap_label('찾을 표현')
    adb('shell', 'input', 'text', PHRASE)
    tap_label('변경할 표현')
    adb('shell', 'input', 'text', REPLACEMENT)
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
    tap_label('저장')
    wait_for(label(PHRASE), PHRASE)
    evidence(output, '01-baseline-user-glossary')
    before = assert_saved(output, 'baseline')

    # Replace the application signed with the exact same disposable key. Preserve its data.
    assert 'Success' in adb('install', '-r', optimized)
    assert version_code() == 33
    reopen()
    wait_for(label(HOME), HOME)
    assert 'android.permission.RECORD_AUDIO: granted=true' in adb('shell', 'dumpsys', 'package', APP)
    tap_label('사용자 용어집 · 자막 프리셋', scroll=True)
    wait_for(label(PHRASE), PHRASE)
    wait_for(label("변경할 표현: " + REPLACEMENT), REPLACEMENT)
    evidence(output, '02-updated-user-glossary')
    after = assert_saved(output, 'optimized')
    assert before['glossary_sha256'] == after['glossary_sha256']
    assert before['settings_sha256'] == after['settings_sha256']
    print('Verified exact signed ARM64 release upgrade 32→33, retained microphone permission/settings, and persisted user glossary.',
          flush=True)


if __name__ == '__main__':
    output = Path(sys.argv[3]).resolve()
    output.mkdir(parents=True, exist_ok=True)
    try:
        run(sys.argv[1], sys.argv[2], output)
    except Exception:
        try:
            evidence(output, 'upgrade-failure')
            (output / 'logcat.txt').write_text(adb('logcat', '-d', '-t', '500'), encoding='utf-8')
        except Exception as problem:
            print(f'Could not save emulator diagnostics: {problem}', file=sys.stderr)
        raise
