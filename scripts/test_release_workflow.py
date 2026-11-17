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

    def test_internal_signed_build_keeps_native_heap_guard_and_no_automatic_release(self):
        workflow = (ROOT / ".github/workflows/play-internal.yml").read_text()
        build_step = workflow.split(
            "- name: Build release AAB and APK with the existing release signer", 1
        )[1].split("- name: Verify AAB and APK match the existing app certificate", 1)[0]
        self.assertIn("--max-workers=2", build_step)
        self.assertIn("-Dorg.gradle.jvmargs='-Xmx4g -Dfile.encoding=UTF-8'", build_step)
        self.assertIn("lintRelease bundleRelease assembleRelease", build_step)
        self.assertIn("final_release_approved", workflow)
        self.assertIn("internal_qa_approved", workflow)
        self.assertIn("workflow_dispatch:", workflow)
        self.assertNotIn("workflow_run:", workflow.split("permissions:", 1)[0])

    def test_debug_package_is_separate_and_release_has_no_debug_signer_fallback(self):
        gradle = (ROOT / "app/build.gradle.kts").read_text()
        self.assertIn('applicationIdSuffix = ".debug"', gradle)
        self.assertIn('versionNameSuffix = "-test-only"', gradle)
        self.assertIn('if (name == "preReleaseBuild") dependsOn(verifyReleaseSigning)', gradle)
        self.assertNotIn('signingConfigs.getByName("debug")', gradle)


if __name__ == "__main__":
    unittest.main()
