"""RU-03/04/05: verify separate signed-build, internal and closed-stage guards."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
WF = ROOT / ".github" / "workflows"


class ReleaseStageContractTests(unittest.TestCase):
    def test_build_only_request_has_no_play_auth(self):
        text = (WF / "release-build.yml").read_text(encoding="utf-8")
        for item in ("workflow_dispatch:", "expected_main_sha:", "batch_id:", "final_batch_approved:"):
            self.assertIn(item, text)
        for item in ("google-github-actions/auth@", "androidpublisher.googleapis.com",
                     "inputs[mode]=deploy", "inputs[deploy]=true"):
            self.assertNotIn(item, text)

    def test_ops04_requires_exact_existing_artifact(self):
        workflow = (WF / "play-internal-release.yml").read_text(encoding="utf-8")
        verifier = (ROOT / "scripts/ops04/internal.py").read_text(encoding="utf-8")
        for item in ("build_run_id:", "artifact_name:", "aab_sha256:",
                     "expected_main_sha:", "version_code:", "version_name:"):
            self.assertIn(item, workflow)
        self.assertIn("actions/download-artifact@", workflow)
        self.assertIn("run-id: ${{ inputs.build_run_id }}", workflow)
        self.assertIn("inputs.mode == 'publish'", workflow)
        for item in ("Expected exactly one AAB", "Downloaded AAB SHA-256 differs",
                     "AAB applicationId, versionCode or versionName mismatch",
                     'play_uploaded": False'):
            self.assertIn(item, verifier)

    def test_ops05_promotes_exact_internal_version(self):
        text = (WF / "play-closed-release.yml").read_text(encoding="utf-8")
        script = (ROOT / "scripts/ops05/promotion.py").read_text(encoding="utf-8")
        self.assertIn("inputs.mode == 'promote'", text)
        self.assertIn("PROMOTE_VERIFIED_INTERNAL_VERSION", script)
        self.assertIn('aab_rebuilt": False', script)
        for item in ("bundleRelease", "/bundles:upload", "assembleRelease"):
            self.assertNotIn(item, script)


if __name__ == "__main__":
    unittest.main()
