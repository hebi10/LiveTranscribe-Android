"""Regression guard: coding and merging work must not publish to Google Play."""
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[3]
WORKFLOWS = ROOT / ".github" / "workflows"


def workflow(name):
    return (WORKFLOWS / name).read_text(encoding="utf-8")


class ReleaseFreezeTests(unittest.TestCase):
    def test_only_manual_dispatch_can_start_internal_upload_or_store_image_sync(self):
        for name in ("play-internal.yml", "play-drive-assets.yml"):
            raw = workflow(name)
            event_config = raw.split("\npermissions:", 1)[0]
            self.assertIn("  workflow_dispatch:", event_config, name)
            self.assertNotIn("  workflow_run:", event_config, name)
            self.assertNotIn("  push:", event_config, name)

    def test_binary_upload_requires_explicit_final_approval(self):
        raw = workflow("play-internal.yml")
        self.assertIn("      final_release_approved:", raw)
        self.assertIn("        default: false", raw)
        self.assertIn("if: inputs.mode == 'deploy' || inputs.mode == 'initialize'", raw)
        self.assertIn('FINAL_RELEASE_APPROVED: ${{ inputs.final_release_approved }}', raw)
        self.assertIn('if [[ "$FINAL_RELEASE_APPROVED" != "true" ]]', raw)
        self.assertIn("    if: github.event_name == 'workflow_dispatch'", raw)

    def test_legacy_auto_alpha_routes_cannot_run_mutating_jobs(self):
        for name in (
            "play-alpha-approved.yml",
            "play-alpha-draft-direct.yml",
            "play-release-01b.yml",
            "play-ui-gap-closed.yml",
        ):
            text = workflow(name)
            jobs = text.split("\njobs:\n", 1)[1]
            before_next_job = jobs.split("\n    runs-on:", 1)[0]
            self.assertIn("if: ${{ false }}", before_next_job, name)

    def test_final_manual_play_promotion_is_still_available(self):
        raw = workflow("play-promote.yml")
        head = raw.split("\npermissions:", 1)[0]
        self.assertIn("  workflow_dispatch:", head)
        self.assertIn("options: [check, promote]", head)
        self.assertNotIn("  workflow_run:", head)


if __name__ == "__main__":
    unittest.main()
