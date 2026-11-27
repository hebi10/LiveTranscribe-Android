"""Fetch and verify the seven existing model files for disposable offline testing."""
import hashlib
import re
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path


catalog, target = map(Path, sys.argv[1:3])
entries = re.findall(
    r'OfflineModelFile\(\s*"[^"]+", "([^"]+)",\s*"(https://huggingface\.co/[^"]+)",\s*(\d+)L, "([a-f0-9]{64})"',
    catalog.read_text(encoding='utf-8'),
)
assert len(entries) == 7, 'Expected exact offline model catalog'


def prepare(entry):
    relative, url, byte_count, expected = entry
    path = target / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(['curl', '--fail', '--silent', '--show-error', '--location', '--retry', '2',
                    '--connect-timeout', '20', '--max-time', '650', '--output', str(path), url], check=True)
    assert path.stat().st_size == int(byte_count), relative
    with path.open('rb') as stream:
        assert hashlib.file_digest(stream, 'sha256').hexdigest() == expected, relative
    print(f'Verified pinned source {relative} ({byte_count} bytes)', flush=True)


with ThreadPoolExecutor(max_workers=4) as pool:
    list(pool.map(prepare, entries))
