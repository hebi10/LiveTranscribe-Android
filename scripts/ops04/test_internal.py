"""OPS-04 offline tests. No Google Play or GitHub mutations."""
import importlib.util
import os
from pathlib import Path
import re
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "ops04" / "internal.py"
spec = importlib.util.spec_from_file_location("ops04_internal", SCRIPT)
internal = importlib.util.module_from_spec(spec)
spec.loader.exec_module(internal)

class Ops04SafetyTests(unittest.TestCase):
    def environment(self, mode="publish"):
        return {
            "GITHUB_EVENT_NAME": "workflow_dispatch",
            "GITHUB_REF": "refs/heads/main",
            "GITHUB_SHA": "a" * 40,
            "EXPECTED_MAIN_SHA": "a" * 40,
            "GITHUB_REPOSITORY": "hebi10/fixture",
            "BATCH_ID": "2026-11-13-fixture",
            "BUILD_RUN_ID": "12345",
            "ARTIFACT_NAME": "verified-signed-aab-123",
            "VERSION_CODE": "123",
            "VERSION_NAME": "1.0.0",
            "AAB_SHA256": "b" * 64,
            "DEPLOY_MODE": mode,
            "FINAL_RELEASE_APPROVED": "false",
            "APPROVAL_PHRASE": "",
            "BUILD_WORKFLOW_FILE": ".github/workflows/play-internal.yml",
        }

    def test_publish_requires_separate_approval_before_network(self):
        with patch.dict(os.environ, self.environment(), clear=True):
            with patch.object(internal, "github") as api:
                with self.assertRaises(internal.GateError):
                    internal.guard()
                api.assert_not_called()

    def test_bad_main_sha_rejected_before_network(self):
        data = self.environment(mode="check")
        data["EXPECTED_MAIN_SHA"] = "c" * 40
        with patch.dict(os.environ, data, clear=True):
            with patch.object(internal, "github") as api:
                with self.assertRaises(internal.GateError):
                    internal.guard()
                api.assert_not_called()

    def test_invalid_artifact_name_rejected(self):
        data = self.environment(mode="check")
        data["ARTIFACT_NAME"] = "../other-run"
        with patch.dict(os.environ, data, clear=True):
            with patch.object(internal, "github") as api:
                with self.assertRaises(internal.GateError):
                    internal.guard()
                api.assert_not_called()

    def valid_run(self, data):
        return {
            "id": int(data["BUILD_RUN_ID"]), "head_sha": data["EXPECTED_MAIN_SHA"],
            "head_branch": "main", "event": "workflow_dispatch",
            "status": "completed", "conclusion": "success",
            "repository": {"full_name": data["GITHUB_REPOSITORY"]},
            "path": data["BUILD_WORKFLOW_FILE"],
        }

    def artifact_inventory(self, data):
        return {"total_count": 1, "artifacts": [
            {"name": data["ARTIFACT_NAME"], "expired": False, "size_in_bytes": 196392699}]}

    def test_real_eleven_digit_actions_id_passes_exact_source_guards(self):
        data = self.environment()
        data.update(BUILD_RUN_ID="45338752575", FINAL_RELEASE_APPROVED="true",
                    APPROVAL_PHRASE="PUBLISH_VERIFIED_INTERNAL_AAB")
        repo = data["GITHUB_REPOSITORY"]
        with patch.dict(os.environ, data, clear=True), patch.object(internal, "github", side_effect=[
            {"object": {"sha": data["EXPECTED_MAIN_SHA"]}},
            self.valid_run(data), self.artifact_inventory(data),
        ]) as api:
            internal.guard()
            self.assertEqual([c.args[0] for c in api.call_args_list], [
                f"repos/{repo}/git/ref/heads/main",
                f"repos/{repo}/actions/runs/45338752575",
                f"repos/{repo}/actions/runs/45338752575/artifacts?per_page=100",
            ])

    def test_invalid_or_unbounded_run_ids_rejected_before_network(self):
        for value in ("", "0", "01", "-1", "1/2", "1e11", "１２", "9223372036854775808", "1" * 20):
            data = self.environment(mode="check")
            data["BUILD_RUN_ID"] = value
            with self.subTest(value=value), patch.dict(os.environ, data, clear=True), \
                    patch.object(internal, "github") as api:
                with self.assertRaisesRegex(internal.GateError, "Invalid source run ID"):
                    internal.guard()
                api.assert_not_called()

    def test_widened_id_never_accepts_wrong_run_or_source_or_incomplete_inventory(self):
        data = self.environment(mode="check")
        data["BUILD_RUN_ID"] = "45338752575"
        for change in ({"id": 45338752576}, {"id": True}, {"head_sha": "c" * 40},
                       {"event": "push"}, {"conclusion": "failure"}, {"path": ".github/workflows/other.yml"}):
            run = dict(self.valid_run(data), **change)
            with self.subTest(change=change), patch.dict(os.environ, data, clear=True), \
                    patch.object(internal, "github", side_effect=[
                        {"object": {"sha": data["EXPECTED_MAIN_SHA"]}}, run
                    ]) as api:
                with self.assertRaises(internal.GateError):
                    internal.guard()
                self.assertEqual(api.call_count, 2)
        inventory = self.artifact_inventory(data)
        inventory["total_count"] = 2
        with patch.dict(os.environ, data, clear=True), patch.object(internal, "github", side_effect=[
            {"object": {"sha": data["EXPECTED_MAIN_SHA"]}}, self.valid_run(data), inventory
        ]):
            with self.assertRaisesRegex(internal.GateError, "Unique, unexpired"):
                internal.guard()

    def test_existing_upload_certificate_is_available_in_both_verification_jobs(self):
        source = (ROOT / ".github" / "workflows" / "play-internal-release.yml").read_text(encoding="utf-8")
        header, verify_and_publish = source.split("\njobs:\n", 1)
        verify, publish = verify_and_publish.split("\n  publish:\n", 1)
        self.assertNotIn("secrets.ANDROID_RELEASE_CERT_SHA256", header)
        for job in (verify, publish):
            self.assertIn("environment: google-play", job)
            self.assertIn("PINNED_UPLOAD_CERT: ${{ secrets.ANDROID_RELEASE_CERT_SHA256 }}", job)
            self.assertIn("ANDROID_RELEASE_CERT_SHA256: ${{ secrets.ANDROID_RELEASE_CERT_SHA256 }}", job)
        self.assertNotIn("id-token: write", verify)
        self.assertNotIn("google-github-actions/auth@", verify)

    def test_missing_bundle_fails_closed(self):
        with tempfile.TemporaryDirectory() as folder:
            with patch.object(internal, "BUNDLE_PATH", Path(folder)):
                with self.assertRaises(internal.GateError):
                    internal.verify()

    def test_sha256_streaming_matches_reference(self):
        with tempfile.TemporaryDirectory() as folder:
            bundle = Path(folder) / "x.aab"
            bundle.write_bytes(b"ops04-immutable-byte-fixture")
            import hashlib
            self.assertEqual(internal.digest(bundle), hashlib.sha256(bundle.read_bytes()).hexdigest())

    def test_workflow_never_builds_or_starts_on_push(self):
        source = (ROOT / ".github" / "workflows" / "play-internal-release.yml").read_text(encoding="utf-8")
        self.assertIn("  workflow_dispatch:", source)
        self.assertNotRegex(source, r"(?m)^  (push|workflow_run|issue_comment|schedule|pull_request):")
        self.assertIn("inputs.mode == 'publish'", source)
        self.assertIn("PUBLISH_VERIFIED_INTERNAL_AAB", source)
        self.assertIn("scripts/ops04/internal.py verify", source)
        self.assertNotIn("bundleRelease", source)
        self.assertNotIn("assembleRelease", source)
        self.assertNotIn("gh release create", source)
        self.assertIn("androidpublisher", source)

if __name__ == "__main__":
    unittest.main()
