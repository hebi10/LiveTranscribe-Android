"""Regression guard: coding and merging work must not publish to Google Play."""
import os
import pathlib
import subprocess
import tempfile
import textwrap
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


    def test_internal_qa_override_is_manual_internal_only(self):
        raw = workflow("play-internal.yml")
        self.assertIn("      internal_qa_approved:", raw)
        self.assertIn('INTERNAL_QA_APPROVED: ${{ inputs.internal_qa_approved }}', raw)
        self.assertIn('RELEASE_MODE: ${{ inputs.mode }}', raw)
        self.assertNotIn("  push:", raw.split("\npermissions:", 1)[0])
        approval = raw.split(
            "      - name: Require explicit final batch release approval before any Play binary upload\n", 1
        )[1].split("      - name: Require exact main source", 1)[0]
        script = textwrap.dedent(approval.split("        run: |\n", 1)[1])
        cases = (
            ("deploy", "false", "true", True),
            ("deploy", "false", "false", False),
            ("initialize", "false", "true", False),
            ("initialize", "true", "false", True),
            ("deploy", "true", "false", True),
        )
        with tempfile.TemporaryDirectory() as folder:
            summary = pathlib.Path(folder) / "summary"
            for mode, final, qa, expected in cases:
                with self.subTest(mode=mode, final=final, qa=qa):
                    result = subprocess.run(
                        ["bash", "-e", "-u", "-o", "pipefail", "-c", script],
                        env={**os.environ, "RELEASE_MODE": mode,
                             "FINAL_RELEASE_APPROVED": final,
                             "INTERNAL_QA_APPROVED": qa,
                             "GITHUB_STEP_SUMMARY": str(summary)},
                        text=True, capture_output=True, check=False,
                    )
                    self.assertEqual(result.returncode == 0, expected, result.stderr)

    def test_legacy_auto_alpha_routes_cannot_run_mutating_jobs(self):
        for name in (
            "play-alpha-approved.yml",
            "play-alpha-draft-direct.yml",
            "play-release-01b.yml",
            "play-ui-gap-closed.yml",
        ):
            text = workflow(name)
            event_config = text.split("\npermissions:", 1)[0]
            self.assertNotIn("  workflow_dispatch:", event_config, name)
            self.assertTrue("  workflow_run:" in event_config or "  push:" in event_config, name)
            jobs = text.split("\njobs:\n", 1)[1]
            before_next_job = jobs.split("\n    runs-on:", 1)[0]
            self.assertIn("if: github.event_name == 'workflow_dispatch'", before_next_job, name)

    def test_all_manual_play_mutations_have_final_approval_guard(self):
        for name, mode in (
            ("play-listing.yml", "apply"),
            ("play-store-01.yml", "apply"),
            ("play-promote.yml", "promote"),
        ):
            raw = workflow(name)
            self.assertIn("      final_release_approved:", raw, name)
            self.assertIn("        default: false", raw, name)
            self.assertIn("if: inputs.mode == '" + mode + "'", raw, name)
            self.assertIn('FINAL_RELEASE_APPROVED: ${{ inputs.final_release_approved }}', raw, name)
            self.assertIn('if [[ "$FINAL_RELEASE_APPROVED" != "true" ]]', raw, name)

        images = workflow("play-drive-assets.yml")
        self.assertIn("      final_release_approved:", images)
        self.assertIn('FINAL_RELEASE_APPROVED: ${{ inputs.final_release_approved }}', images)
        self.assertIn('if [[ "$FINAL_RELEASE_APPROVED" != "true" ]]', images)

    def test_final_manual_play_promotion_is_still_available(self):
        raw = workflow("play-promote.yml")
        head = raw.split("\npermissions:", 1)[0]
        self.assertIn("  workflow_dispatch:", head)
        self.assertIn("options: [check, promote]", head)
        self.assertNotIn("  workflow_run:", head)


if __name__ == "__main__":
    unittest.main()
