"""Exercise Android capture consent and model-generated subtitles on an offline QA device."""
import json
import re
import runpy
import subprocess
import sys
import time
from pathlib import Path


utils = runpy.run_path(str(Path(__file__).with_name('dex04-live-upgrade.py')))
APP = utils['APP']
adb = utils['adb']
launch = utils['launch']
reopen = utils['reopen']
wait_for = utils['wait_for']
tap = utils['tap']
tap_label = utils['tap_label']
label = utils['label']
evidence = utils['evidence']
PLAYER = 'qa.dex04.playback'
TEST_MODEL_DIR = f'/data/user/0/{APP}/no_backup/ai-models'
HISTORY = f'/data/user/0/{APP}/no_backup/transcript_history.json'
START = '팝업 자막 시작'
DENIED = '시스템 오디오 캡처 권한이 허용되지 않았습니다.'


def wait_until(read, predicate, label, *, timeout=55):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        result = read()
        if predicate(result):
            return result
        time.sleep(2)
    raise AssertionError(f'Missing proof: {label}')


def setup_models(models):
    no_backup = str(Path(TEST_MODEL_DIR).parent)
    adb('shell', 'mkdir', '-p', TEST_MODEL_DIR)
    adb('push', '--sync', str(models / 'qwen3-asr'), str(models / 'translation'), TEST_MODEL_DIR, timeout=350)
    owner = adb('shell', 'stat', '-c', '%u:%g', f'/data/user/0/{APP}').strip()
    assert re.fullmatch(r'\d+:\d+', owner) and not owner.startswith('0:'), owner
    context = adb('shell', 'ls', '-dZ', f'/data/user/0/{APP}').strip().split()[0]
    assert context.startswith('u:object_r:app_data_file:'), context
    adb('shell', 'chown', '-R', owner, no_backup)
    adb('shell', 'chcon', '-R', context, no_backup)
    count = int(adb('shell', f'find {TEST_MODEL_DIR} -type f | wc -l').strip())
    assert count == 7, count
    adb('shell', 'test', '-f', f'{TEST_MODEL_DIR}/qwen3-asr/decoder.int8.onnx')


def project_active():
    return APP in adb('shell', 'dumpsys', 'media_projection')


def active_projection_without_process_exit():
    try:
        assert adb('shell', 'pidof', APP).strip(), 'App exited after granting screen capture'
    except subprocess.CalledProcessError as exit_error:
        raise RuntimeError('App exited after granting screen capture') from exit_error
    return project_active()


def wait_for_consent(button):
    allowed = button == 'allow'
    suffix = 'button1' if allowed else 'button2'
    captions = {'start now', '지금 시작'} if allowed else {'cancel', '취소'}
    point = wait_for(
        lambda n: n.get('package') == 'com.android.systemui'
        and (n.get('resource-id', '').endswith(':' + 'id/' + suffix)
             or n.get('text', '').strip().casefold() in captions),
        f'Android MediaProjection {button} button',
    )
    return point


def overlay_windows():
    dump = adb('shell', 'dumpsys', 'window', 'windows')
    windows = re.split(r'(?m)^\s*Window #\d+ Window\{', dump)
    return '\n'.join(w for w in windows[1:] if APP in w.splitlines()[0]
                     and 'APPLICATION_OVERLAY' in w and 'mHasSurface=true' in w)


def read_transcript():
    try:
        return json.loads(adb('exec-out', 'cat', HISTORY))
    except (ValueError, subprocess.CalledProcessError):
        return []


def translated(entry):
    english = entry.get('originalText', '').casefold()
    korean = entry.get('translatedText', '')
    tokens = {'hello', 'world', 'weather', 'sunny', 'translation', 'test'}
    return len(tokens & set(re.findall(r'[a-z]+', english))) >= 2 and bool(re.search(r'[가-힣]{2}', korean))


def run(apk, audio_fixture, models, output):
    assert any(adb('shell', 'getprop', key).strip() == '1'
               for key in ('ro.kernel.qemu', 'ro.boot.qemu')), 'Use only a disposable emulator'
    assert 'arm64-v8a' in adb('shell', 'getprop', 'ro.product.cpu.abilist').strip().split(',')
    adb('root')
    adb('wait-for-device')
    assert adb('shell', 'id', '-u').strip() == '0'
    assert 'Success' in adb('install', apk)
    assert 'Success' in adb('install', audio_fixture)
    adb('shell', 'svc', 'wifi', 'disable')
    adb('shell', 'svc', 'data', 'disable')
    adb('shell', 'pm', 'grant', APP, 'android.permission.RECORD_AUDIO')
    adb('shell', 'appops', 'set', APP, 'SYSTEM_ALERT_WINDOW', 'allow')
    setup_models(Path(models))
    launch()

    assert not project_active()
    tap_label('영어', scroll=True, attempts=15)
    tap_label('세부 설정 열기', scroll=True)
    tap_label('번역 기록 저장', scroll=True, attempts=24)
    time.sleep(2)
    reopen()
    wait_for(label(START), 'enabled popup start after app verifies model hashes', attempts=42)
    evidence(output, '01-pinned-models-ready')

    tap_label(START)
    cancel = wait_for_consent('deny')
    evidence(output, '02-Android-capture-consent-deny')
    tap(cancel)
    wait_for(label(DENIED), 'app notification after denying projection', scroll=True)
    assert not project_active()
    evidence(output, '03-app-denial-and-retry')
    reopen()
    tap_label(START, attempts=30)
    allow = wait_for_consent('allow')
    evidence(output, '04-Android-capture-consent-allow')
    tap(allow)
    wait_until(active_projection_without_process_exit, bool, 'active app MediaProjection after consent', timeout=90)
    found = wait_until(overlay_windows, bool, 'visible app WindowManager overlay', timeout=180)
    (output / 'overlay-windows.txt').write_text(found, encoding='utf-8')
    assert 'Status: ok' in adb('shell', 'am', 'start', '-W', '-n', f'{PLAYER}/.PlayerActivity')
    transcript = wait_until(read_transcript, lambda rows: any(translated(item) for item in rows),
                            'ASR from local audio playback and Korean inference retained in history', timeout=390)
    selected = [row for row in transcript if translated(row)]
    (output / 'synthetic-clip-translation.json').write_text(
        json.dumps(selected, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    assert overlay_windows(), 'The app overlay must remain present during real captions'
    evidence(output, '05-actual-subtitles-over-test-player')
    adb('shell', 'am', 'force-stop', PLAYER)
    launch()
    tap_label('번역 중지', scroll=True)
    wait_until(project_active, lambda present: not present, 'release MediaProjection after stop', timeout=100)
    wait_until(overlay_windows, lambda windows: not windows, 'close the app overlay', timeout=100)
    tap_label(START, scroll=True, attempts=24)
    retry = wait_for_consent('deny')
    evidence(output, '06-relaunch-projection-after-stopping')
    tap(retry)
    assert not project_active()
    print('Verified Android screen-capture denial/allow, actual other-app audio capture with offline Korean subtitles, overlay close, and restart consent.', flush=True)


if __name__ == '__main__':
    destination = Path(sys.argv[4]).resolve()
    destination.mkdir(parents=True, exist_ok=True)
    try:
        run(sys.argv[1], sys.argv[2], sys.argv[3], destination)
    except Exception:
        for filename, command in [('logcat.txt', ('logcat', '-d', '-t', '800')),
                                  ('audio-routing.txt', ('shell', 'dumpsys', 'media.audio_policy')),
                                  ('projection.txt', ('shell', 'dumpsys', 'media_projection')),
                                  ('native-crash.txt', ('logcat', '-d', '-b', 'crash')),
                                  ('tombstone.txt', ('exec-out', 'cat', '/data/tombstones/tombstone_00'))]:
            try:
                (destination / filename).write_text(adb(*command), encoding='utf-8')
            except Exception:
                pass
        try:
            evidence(destination, 'audio-qa-failure')
        except Exception:
            pass
        raise
