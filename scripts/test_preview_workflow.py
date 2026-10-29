"""Publishing stays manual, main-only, and separate from release-key updates."""
from hashlib import sha256
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


def job_section(workflow, name):
    match = re.search(rf"^  {re.escape(name)}:\n(.*?)(?=^  [a-z][a-z-]*:\n|\Z)", workflow, re.MULTILINE | re.DOTALL)
    return "" if match is None else match.group(1)


def publish_command(workflow):
    preview = job_section(workflow, "preview-publish")
    match = re.search(r"^      - name: Publish the separate-install preview\n.*?^        run: \|\n(.*?)(?=^      -|\Z)", preview, re.MULTILINE | re.DOTALL)
    return "" if match is None else "\n".join(line[10:] for line in match.group(1).splitlines())


class PreviewWorkflowTest(unittest.TestCase):
    def setUp(self):
        self.workflow = (ROOT / ".github/workflows/build.yml").read_text()
        self.preview = job_section(self.workflow, "preview-publish")

    def test_preview_is_explicit_main_only_and_requires_successful_checks(self):
        self.assertRegex(self.workflow, r"publish_preview:\n        description: .*\n        type: boolean\n        required: true\n        default: false")
        self.assertIn("if: github.event_name == 'workflow_dispatch' && inputs.publish_preview && github.ref == 'refs/heads/main'", self.preview)
        self.assertIn("needs: [build, release-request]", self.preview)
        guard = job_section(self.workflow, "release-request")
        self.assertIn("if: github.event_name == 'workflow_dispatch' && (inputs.build_release || inputs.publish_preview)", guard)
        self.assertIn('"$GITHUB_REF" != \'refs/heads/main\'', guard)

    def test_only_preview_publish_job_receives_contents_write(self):
        self.assertEqual(1, self.workflow.count("contents: write"))
        self.assertIn("permissions:\n      contents: write", self.preview)
        self.assertIn("permissions:\n  contents: read", self.workflow)
        self.assertNotIn("${{ secrets.", self.preview)
        self.assertIn("GH_TOKEN: ${{ github.token }}", self.preview)
        self.assertNotIn("contents: write", job_section(self.workflow, "build"))

    def test_preview_uses_this_runs_exact_test_artifact_and_publishes_checksum(self):
        self.assertIn("uses: actions/download-artifact@v4", self.preview)
        self.assertIn("name: hebi-live-translate-TEST-ONLY-debug", self.preview)
        self.assertNotIn("run-id:", self.preview)
        self.assertNotIn("assembleRelease", self.preview)
        self.assertIn("LiveTranslate-v1.3.0-arm64-preview-separate.apk", self.preview)
        self.assertIn('sha256sum "$PREVIEW_APK" > "$PREVIEW_APK.sha256"', self.preview)
        self.assertIn('--prerelease --latest=false --verify-tag', self.preview)
        self.assertIn('"$PREVIEW_APK" "$PREVIEW_APK.sha256"', self.preview)

    def test_notes_identify_separate_package_and_test_signing_limits(self):
        for text in (
            "com.hebi.livetranslate.debug", "기존 앱을 유지",
            "자체 모델과 설정", "정식 앱 업데이트가 아닙니다",
            "테스트용 서명자는 이후 빌드에서 달라질 수",
            "문장 확정", "언어 감지", "종료", "팝업",
        ):
            self.assertIn(text, self.preview)

    def test_existing_tag_blocks_release_creation(self):
        command = publish_command(self.workflow)
        self.assertTrue(command, "Preview publishing command is required")
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            calls = directory / "calls"
            gh = directory / "gh"
            gh.write_text('#!/bin/bash\nprintf "%s\\n" "$*" >> "$GH_CALLS"\n'
                          'if [ "$1" = api ]; then echo "Reference already exists" >&2; exit 1; fi\n'
                          'exit 0\n')
            gh.chmod(0o700)
            (directory / "preview-release").mkdir()
            (directory / "preview-release/LiveTranslate-v1.3.0-arm64-preview-separate.apk").write_bytes(b"fixture APK")
            environment = os.environ.copy()
            environment.update({"PATH": f"{directory}:{environment.get('PATH', '')}",
                                "GH_CALLS": str(calls), "GITHUB_REPOSITORY": "fixture/repository",
                                "GITHUB_SHA": "0" * 40, "GH_TOKEN": "fixture-token"})
            result = subprocess.run(["bash", "-e", "-o", "pipefail", "-c", command],
                                    cwd=directory, env=environment, text=True, capture_output=True)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("already exists", result.stdout + result.stderr)
            invocation = calls.read_text()
            self.assertIn("api --method POST", invocation)
            self.assertIn("ref=refs/tags/v1.3.0-preview", invocation)
            self.assertNotIn("release create", invocation)
            self.assertNotIn("--force", invocation)

    def test_success_attaches_the_exact_apk_and_its_matching_checksum(self):
        command = publish_command(self.workflow)
        self.assertTrue(command, "Preview publishing command is required")
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            calls = directory / "calls"
            gh = directory / "gh"
            gh.write_text('#!/bin/bash\nprintf "%s\\n" "$*" >> "$GH_CALLS"\nexit 0\n')
            gh.chmod(0o700)
            output = directory / "preview-release"
            output.mkdir()
            name = "LiveTranslate-v1.3.0-arm64-preview-separate.apk"
            (output / name).write_bytes(b"fixture APK")
            environment = os.environ.copy()
            environment.update({"PATH": f"{directory}:{environment.get('PATH', '')}",
                                "GH_CALLS": str(calls), "GITHUB_REPOSITORY": "fixture/repository",
                                "GITHUB_SHA": "0" * 40, "GH_TOKEN": "fixture-token"})
            result = subprocess.run(["bash", "-e", "-o", "pipefail", "-c", command],
                                    cwd=directory, env=environment, text=True, capture_output=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(f"{sha256(b'fixture APK').hexdigest()}  {name}\n",
                             (output / f"{name}.sha256").read_text())
            invocation = calls.read_text().splitlines()
            self.assertEqual(2, len(invocation))
            self.assertIn("api --method POST", invocation[0])
            self.assertIn(f"release create v1.3.0-preview {name} {name}.sha256", invocation[1])
            self.assertIn("--prerelease --latest=false --verify-tag", invocation[1])
            self.assertIn("com.hebi.livetranslate.debug", (output / "release-notes.md").read_text())


if __name__ == "__main__":
    unittest.main()
