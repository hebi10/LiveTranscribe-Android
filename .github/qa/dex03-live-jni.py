"""Check native lookups in the actual optimized AAB DEX definitions."""
import re
import subprocess
import sys
from pathlib import Path
from tempfile import TemporaryDirectory
from zipfile import ZipFile

SHERPA = 'Lcom/k2fsa/sherpa/onnx/'
BRIDGE = 'Lcom/charles/livecaptionn/translation/NativeTranslationBridge;'
EXPECTED = {
    SHERPA + 'OfflineRecognizerConfig;': {('featConfig', SHERPA + 'FeatureConfig;'),
                                          ('modelConfig', SHERPA + 'OfflineModelConfig;')},
    SHERPA + 'OfflineModelConfig;': {('qwen3Asr', SHERPA + 'OfflineQwen3AsrModelConfig;'),
                                     ('transducer', SHERPA + 'OfflineTransducerModelConfig;')},
    SHERPA + 'OfflineQwen3AsrModelConfig;': {('encoder', 'Ljava/lang/String;'), ('maxNewTokens', 'I')},
    SHERPA + 'VadModelConfig;': {('sileroVadModelConfig', SHERPA + 'SileroVadModelConfig;'),
                                 ('tenVadModelConfig', SHERPA + 'TenVadModelConfig;')},
    SHERPA + 'SileroVadModelConfig;': {('threshold', 'F')},
    SHERPA + 'SpokenLanguageIdentificationConfig;': {('whisper', SHERPA + 'SpokenLanguageIdentificationWhisperConfig;')},
    SHERPA + 'SpokenLanguageIdentificationWhisperConfig;': {('encoder', 'Ljava/lang/String;')},
    SHERPA + 'OfflineRecognizerResult;': {
        ('<init>', '(Ljava/lang/String;[Ljava/lang/String;[FLjava/lang/String;Ljava/lang/String;Ljava/lang/String;[F)V')},
    BRIDGE: {('create', '(Ljava/lang/String;II)J'), ('translate', '(JJ[BII)[B'),
             ('cancel', '(JJ)V'), ('destroy', '(J)V')},
}


def definitions(bundle_path, dexdump):
    with ZipFile(bundle_path) as bundle, TemporaryDirectory() as temp:
        members = {}
        for name in bundle.namelist():
            if not re.fullmatch(r'base/dex/classes(?:\d+)?\.dex', name):
                continue
            dex_path = bundle.extract(name, temp)
            process = subprocess.Popen([dexdump, dex_path], stdout=subprocess.PIPE, encoding='utf-8', errors='replace')
            owner = None
            member = None
            for line in process.stdout:
                if line.strip().startswith('Class descriptor'):
                    owner = line.split("'")[1]
                    member = None
                elif owner in EXPECTED and line.strip().startswith('name '):
                    member = line.split("'")[1]
                elif owner in EXPECTED and member is not None and line.strip().startswith('type '):
                    members.setdefault(owner, set()).add((member, line.split("'")[1]))
            if process.wait() != 0:
                raise RuntimeError(f'dexdump failed on {Path(dex_path).name}')
    return members


if __name__ == '__main__':
    observed = definitions(*sys.argv[1:3])
    for owner, expected in EXPECTED.items():
        missing = expected - observed.get(owner, set())
        assert not missing, f'Missing JNI entry on {owner}: {sorted(missing)}'
    print(f'Verified {sum(map(len, EXPECTED.values()))} JNI-resolved members in optimized DEX')
