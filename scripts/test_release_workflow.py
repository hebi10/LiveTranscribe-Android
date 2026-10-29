"""Keep signing secrets out of PR jobs and prevent unsigned update artifacts."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class ReleaseWorkflowTest(unittest.TestCase):
    def test_regular_build_and_pr_jobs_have_no_signing_secrets(self):
        workflow = (ROOT / ".github/workflows/build.yml").read_text()
        ordinary_build = workflow.split("  signed-release:", 1)[0]
        self.assertNotIn("${{ secrets.", ordinary_build)
        self.assertNotIn("pull_request_target", workflow)
        self.assertIn("TEST-ONLY-debug", ordinary_build)

    def test_release_requires_manual_main_branch_and_successful_regular_checks(self):
        workflow = (ROOT / ".github/workflows/build.yml").read_text()
        release = workflow.split("  signed-release:", 1)[1].split("  preview-publish:", 1)[0]
        self.assertIn("if: github.event_name == 'workflow_dispatch' && inputs.build_release && github.ref == 'refs/heads/main'", release)
        self.assertIn("needs: [build, release-request]", release)
        self.assertIn("environment: release-signing", release)
        self.assertLess(release.index("release_signing.py prepare"), release.index("assembleRelease"))
        self.assertLess(release.index("release_signing.py verify-apk"), release.index("actions/upload-artifact"))
        self.assertLess(release.index("verify_apk_assets.py"), release.index("actions/upload-artifact"))
        self.assertIn("if: always()", release)
        self.assertNotIn("app-debug.apk", release)
        self.assertNotIn("app-release-unsigned.apk", release)

    def test_debug_package_is_separate_and_release_has_no_debug_signer_fallback(self):
        gradle = (ROOT / "app/build.gradle.kts").read_text()
        self.assertIn('applicationIdSuffix = ".debug"', gradle)
        self.assertIn('versionNameSuffix = "-test-only"', gradle)
        self.assertIn('if (name == "preReleaseBuild") dependsOn(verifyReleaseSigning)', gradle)
        self.assertNotIn('signingConfigs.getByName("debug")', gradle)


if __name__ == "__main__":
    unittest.main()
