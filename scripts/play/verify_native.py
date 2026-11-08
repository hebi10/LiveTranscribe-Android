#!/usr/bin/env python3
"""Verify packaged ELF LOAD segments, AAB 16 KB intent and real APK ZIP alignment.

Based on https://developer.android.com/guide/practices/page-sizes . These static
checks do not replace execution on a 16 KB Android device.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import subprocess
import sys
import zipfile

PAGE = 16384


class AlignmentError(RuntimeError):
    pass


def elf_load_alignments(raw):
    if len(raw) < 64 or raw[:4] != b'\x7fELF' or raw[4] not in (1, 2) or raw[5] not in (1, 2):
        raise AlignmentError('Malformed native ELF library')
    endian = '<' if raw[5] == 1 else '>'
    is64 = raw[4] == 2
    phoff = struct.unpack_from(endian + ('Q' if is64 else 'I'), raw, 32 if is64 else 28)[0]
    phentsize, phnum = struct.unpack_from(endian + 'HH', raw, 54 if is64 else 42)
    fmt = endian + ('IIQQQQQQ' if is64 else 'IIIIIIII')
    if phentsize < struct.calcsize(fmt) or not phnum or phoff + phnum * phentsize > len(raw):
        raise AlignmentError('Invalid native ELF program header table')
    values = []
    for index in range(phnum):
        header = struct.unpack_from(fmt, raw, phoff + index * phentsize)
        if header[0] != 1:
            continue
        offset, address, alignment = (header[2], header[3], header[7]) if is64 else (header[1], header[2], header[7])
        if alignment < PAGE or alignment & (alignment - 1) or (address - offset) % alignment:
            raise AlignmentError('Native ELF PT_LOAD is not aligned to at least 16 KB')
        values.append(alignment)
    if not values:
        raise AlignmentError('Native ELF has no PT_LOAD segments')
    return values


def inspect_archive(path, apk=False):
    libraries = []
    with zipfile.ZipFile(path) as archive, open(path, 'rb') as raw_file:
        if len(archive.namelist()) != len(set(archive.namelist())):
            raise AlignmentError('Duplicate ZIP entries are forbidden')
        for info in archive.infolist():
            if not info.filename.endswith('.so'):
                continue
            try:
                values = elf_load_alignments(archive.read(info))
            except AlignmentError as error:
                raise AlignmentError(f'{info.filename}: {error}') from None
            if apk and info.compress_type == zipfile.ZIP_STORED:
                raw_file.seek(info.header_offset)
                header = raw_file.read(30)
                if len(header) != 30 or header[:4] != b'PK\x03\x04':
                    raise AlignmentError('Invalid ZIP local header')
                name_size, extra_size = struct.unpack_from('<HH', header, 26)
                if (info.header_offset + 30 + name_size + extra_size) % PAGE:
                    raise AlignmentError(f'{info.filename}: APK uncompressed library is not 16 KB ZIP aligned')
            libraries.append({'path': info.filename, 'loadAlignments': values,
                              'sha256': hashlib.sha256(archive.read(info)).hexdigest()})
    if not libraries:
        raise AlignmentError('Expected native libraries are absent; artifact could be wrong')
    return libraries


def verify(bundle, apk, bundletool, zipalign, run=subprocess.run):
    aab_libraries = inspect_archive(bundle)
    apk_libraries = inspect_archive(apk, apk=True)
    def normalized(items):
        return {item['path'].split('lib/', 1)[-1]: item['sha256'] for item in items}
    if normalized(aab_libraries) != normalized(apk_libraries):
        raise AlignmentError('AAB and APK native libraries differ')
    # Artifact content, rather than dependency version claims, is the source of truth.
    config = run(['java', '-jar', str(bundletool), 'dump', 'config', '--bundle=' + str(bundle)], capture_output=True, check=False, text=True)
    if config.returncode or 'PAGE_ALIGNMENT_16K' not in config.stdout or 'PAGE_ALIGNMENT_4K' in config.stdout:
        raise AlignmentError('AAB does not explicitly request PAGE_ALIGNMENT_16K')
    aligned = run([str(zipalign), '-c', '-P', '16', '-v', '4', str(apk)], capture_output=True, check=False)
    if aligned.returncode:
        raise AlignmentError('Android zipalign -c -P 16 rejected the real APK')
    return {'status': 'static_native_alignment_verified', 'pageSize': PAGE,
            'aabLibraries': aab_libraries, 'apkLibraries': apk_libraries,
            'deviceExecution': 'not_verified'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bundle', type=Path, required=True)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--bundletool', type=Path, required=True)
    parser.add_argument('--zipalign', type=Path, required=True)
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    try:
        report = verify(args.bundle, args.apk, args.bundletool, args.zipalign)
        if args.report:
            args.report.parent.mkdir(parents=True, exist_ok=True)
            args.report.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
        print(json.dumps(report))
        return 0
    except (AlignmentError, OSError, ValueError, zipfile.BadZipFile, struct.error) as error:
        message = str(error) if isinstance(error, AlignmentError) else 'Invalid or missing artifact/tool input'
        print('Native alignment verification failed: ' + message, file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
