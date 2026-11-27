"""Inspect the actual ARM64 QA release on a disposable Android 11 emulator."""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path


APP = 'com.hebi.livetranslate'
UI_DUMP = '/sdcard/dex03-live.xml'


def adb(*args, binary=False):
    return subprocess.run(['adb', *args], check=True, capture_output=True,
                          text=not binary, timeout=45).stdout


def hierarchy():
    adb('shell', 'uiautomator', 'dump', UI_DUMP)
    return adb('exec-out', 'cat', UI_DUMP)


def evidence(folder, name):
    (folder / f'{name}.png').write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
    (folder / f'{name}.xml').write_text(hierarchy(), encoding='utf-8')


def find(predicate, label, *, scroll=False):
    size = adb('shell', 'wm', 'size')
    width, height = map(int, re.findall(r'(\d+)x(\d+)', size)[-1])
    for i in range(10):
        try:
            for node in ET.fromstring(hierarchy()).iter('node'):
                if not predicate(node):
                    continue
                bounds = [int(n) for n in re.findall(r'\d+', node.get('bounds', ''))]
                if len(bounds) == 4 and bounds[0] < bounds[2] and bounds[1] < bounds[3]:
                    return ((bounds[0] + bounds[2]) // 2, (bounds[1] + bounds[3]) // 2)
        except (ET.ParseError, subprocess.CalledProcessError):
            continue
        if scroll and i >= 2:
            adb('shell', 'input', 'swipe', str(width // 2), str(height * 3 // 4),
                str(width // 2), str(height // 3), '350')
        time.sleep(1)
    raise AssertionError(f'App control not visible: {label}')


def label(expected):
    return lambda node: node.get('text') == expected or node.get('content-desc') == expected


def tap(location):
    adb('shell', 'input', 'tap', *map(str, location))


def main(apk, folder):
    if not any(adb('shell', 'getprop', key).strip() == '1'
               for key in ('ro.kernel.qemu', 'ro.boot.qemu')):
        raise AssertionError('A disposable emulator is required')
    abi_list = adb('shell', 'getprop', 'ro.product.cpu.abilist').strip()
    (folder / 'emulator-abi.txt').write_text(abi_list + '\n', encoding='utf-8')
    assert 'arm64-v8a' in abi_list.split(','), f'Image lacks ARM translation: {abi_list}'
    assert 'Success' in adb('install', apk)
    start = adb('shell', 'am', 'start', '-W', '-n', f'{APP}/com.charles.livecaptionn.MainActivity')
    assert 'Status: ok' in start, start
    print('Verified ARM64 release installation and app startup', flush=True)
    tap(find(label('음성 캡처 권한 허용'), 'microphone permission', scroll=True))
    while_in_use = find(lambda node: node.get('resource-id', '').endswith('permission_allow_foreground_only_button'),
                        'Android microphone permission dialog')
    evidence(folder, '01-microphone-permission')
    tap(while_in_use)
    tap(find(label('자막 표시 권한 허용'), 'overlay permission action', scroll=True))
    find(lambda node: node.get('package') == 'com.android.settings', 'Android overlay settings')
    evidence(folder, '02-overlay-permission-settings')
    settings = adb('shell', 'dumpsys', 'activity', 'activities')
    assert 'com.android.settings' in settings
    print('Verified microphone permission prompt/allow and system overlay settings entry', flush=True)


if __name__ == '__main__':
    output = Path(sys.argv[2])
    try:
        main(sys.argv[1], output)
    except Exception:
        try:
            evidence(output, 'smoke-failure')
            (output / 'smoke-logcat.txt').write_text(adb('logcat', '-d', '-t', '1000'), encoding='utf-8')
        except Exception as error:
            print(f'Gated failure evidence unavailable: {error}', file=sys.stderr)
        raise
