from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).parents[1]))
import verify_native as native


def elf(alignment=16384, loads=1, big_endian=False, bits=64):
    endian = '>' if big_endian else '<'
    header = bytearray(64)
    header[:7] = b'\x7fELF' + bytes((2 if bits == 64 else 1, 2 if big_endian else 1, 1))
    if bits == 64:
        struct.pack_into(endian + 'Q', header, 32, 64)
        struct.pack_into(endian + 'HH', header, 54, 56, loads)
        program = struct.pack(endian + 'IIQQQQQQ', 1, 5, 0, 0, 0, 64, 64, alignment)
    else:
        struct.pack_into(endian + 'I', header, 28, 64)
        struct.pack_into(endian + 'HH', header, 42, 32, loads)
        program = struct.pack(endian + 'IIIIIIII', 1, 0, 0, 0, 64, 64, 5, alignment)
    return bytes(header) + program * loads


class NativeTests(unittest.TestCase):
    def artifacts(self, directory, same=True):
        bundle, apk = Path(directory) / 'release.aab', Path(directory) / 'release.apk'
        with zipfile.ZipFile(bundle, 'w') as archive:
            archive.writestr('base/lib/arm64-v8a/libtest.so', elf())
        entry = zipfile.ZipInfo('lib/arm64-v8a/libtest.so')
        padding = 16384 - 30 - len(entry.filename.encode())
        entry.extra = struct.pack('<HH', 0xCAFE, padding - 4) + bytes(padding - 4)
        with zipfile.ZipFile(apk, 'w') as archive:
            archive.writestr(entry, elf() if same else elf(65536))
        return bundle, apk

    def test_complete_native_verification_requires_actual_artifacts_and_tool_success(self):
        calls = []
        def run(command, **kwargs):
            calls.append(command)
            return subprocess.CompletedProcess(command, 0, stdout='PAGE_ALIGNMENT_16K' if command[0] == 'java' else b'Verification successful')
        with tempfile.TemporaryDirectory() as directory:
            bundle, apk = self.artifacts(directory)
            result = native.verify(bundle, apk, 'bundletool.jar', 'zipalign', run)
        self.assertEqual(result['status'], 'static_native_alignment_verified')
        self.assertEqual(result['deviceExecution'], 'not_verified')
        self.assertEqual(calls[-1][1:6], ['-c', '-P', '16', '-v', '4'])

    def test_aab_and_apk_must_contain_identical_native_libraries(self):
        with tempfile.TemporaryDirectory() as directory:
            bundle, apk = self.artifacts(directory, same=False)
            with self.assertRaisesRegex(native.AlignmentError, 'differ'):
                native.verify(bundle, apk, 'bundletool.jar', 'zipalign')

    def test_bundle_config_4kb_and_failed_zipalign_block_release(self):
        for config, zip_code in (('PAGE_ALIGNMENT_4K', 0), ('PAGE_ALIGNMENT_16K', 1)):
            def run(command, **kwargs):
                return subprocess.CompletedProcess(command, 0 if command[0] == 'java' else zip_code,
                                                   stdout=config if command[0] == 'java' else b'error')
            with tempfile.TemporaryDirectory() as directory:
                bundle, apk = self.artifacts(directory)
                with self.assertRaises(native.AlignmentError):
                    native.verify(bundle, apk, 'bundletool.jar', 'zipalign', run)

    def test_real_elf_header_formats_and_multiple_loads(self):
        for bits in (32, 64):
            for big in (False, True):
                self.assertEqual(native.elf_load_alignments(elf(65536, 2, big, bits)), [65536, 65536])

    def test_4kb_zero_and_non_power_of_two_alignment_fail(self):
        for alignment in (0, 4096, 8192, 24576):
            with self.assertRaises(native.AlignmentError):
                native.elf_load_alignments(elf(alignment))

    def test_truncated_or_wrong_native_library_fails(self):
        for raw in (b'not-ELF', elf()[:70], elf(loads=0)):
            with self.assertRaises(native.AlignmentError):
                native.elf_load_alignments(raw)

    def test_all_libraries_are_checked_including_prebuilt(self):
        with tempfile.NamedTemporaryFile(suffix='.aab') as file:
            with zipfile.ZipFile(file.name, 'w') as archive:
                archive.writestr('base/lib/arm64-v8a/libtranslation.so', elf())
                archive.writestr('base/lib/arm64-v8a/libsherpa-onnx.so', elf(4096))
            with self.assertRaisesRegex(native.AlignmentError, 'libsherpa-onnx'):
                native.inspect_archive(file.name)

    def test_unaligned_stored_apk_library_fails_but_compressed_elf_is_still_checked(self):
        with tempfile.NamedTemporaryFile(suffix='.apk') as file:
            with zipfile.ZipFile(file.name, 'w', compression=zipfile.ZIP_STORED) as archive:
                archive.writestr('lib/arm64-v8a/libtest.so', elf())
            with self.assertRaisesRegex(native.AlignmentError, 'ZIP aligned'):
                native.inspect_archive(file.name, apk=True)
            with zipfile.ZipFile(file.name, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
                archive.writestr('lib/arm64-v8a/libtest.so', elf())
            self.assertEqual(len(native.inspect_archive(file.name, apk=True)), 1)

    def test_native_app_cannot_pass_with_no_libraries(self):
        with tempfile.NamedTemporaryFile(suffix='.apk') as file:
            with zipfile.ZipFile(file.name, 'w') as archive:
                archive.writestr('classes.dex', b'fake')
            with self.assertRaises(native.AlignmentError):
                native.inspect_archive(file.name)
