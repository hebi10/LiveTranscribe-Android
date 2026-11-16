import csv
import json
import tempfile
import unittest
from pathlib import Path

from qa_real_01_b import COLUMNS, analyze, create_template, verify_row

FAKE_SHA = "a" * 40


class QaReal01BTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.folder = Path(self.temp.name)
        self.manifest = self.folder / "qa.csv"
        create_template(self.manifest)

    def rows(self):
        with self.manifest.open(encoding="utf-8-sig", newline="") as stream:
            return list(csv.DictReader(stream))

    def save(self, rows):
        with self.manifest.open("w", encoding="utf-8-sig", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=COLUMNS)
            writer.writeheader()
            writer.writerows(rows)

    def test_template_is_nine_unperformed_sessions(self):
        result = analyze(self.manifest)
        self.assertEqual(len(self.rows()), 9)
        self.assertEqual(result["passSessions"], 0)
        self.assertEqual(len(result["pendingSessions"]), 9)
        self.assertFalse(result["physicalDeviceProven"])
        self.assertFalse(result["releaseApproved"])

    def test_existing_results_never_overwritten(self):
        with self.assertRaises(FileExistsError):
            create_template(self.manifest)

    def test_pass_requires_independent_evidence(self):
        rows = self.rows()
        rows[0]["status"] = "PASS"
        self.save(rows)
        self.assertTrue(analyze(self.manifest)["validationErrors"])

    def test_blocked_sessions_never_qualify(self):
        rows = self.rows()
        rows[0]["status"] = "BLOCKED"
        self.save(rows)
        self.assertEqual(analyze(self.manifest)["status"], "NOT_RUN_OR_INCOMPLETE")

    def test_duplicate_attempts_rejected(self):
        rows = self.rows()
        rows.append(rows[0].copy())
        self.save(rows)
        self.assertTrue(any("duplicate" in item for item in analyze(self.manifest)["validationErrors"]))

    def test_evidence_paths_cannot_escape_manifest_directory(self):
        row = self.rows()[0]
        row.update({"status": "PASS", "device_alias": "DEVICE_A", "clip_id": "CLIP_A",
                    "source_sha": FAKE_SHA, "layout": "popup", "input_mode": "video",
                    "source_language": "en", "model_ready": "YES", "first_translation": "YES",
                    "correction_behavior": "OFF", "evidence_reference": "PRIVATE_A",
                    "performance_csv": "../../private.csv", "caption_json": "caption.json"})
        with self.assertRaisesRegex(ValueError, "inside the manifest"):
            verify_row(row, self.folder)

    def test_mismatched_readability_report_rejected(self):
        row = self.rows()[0]
        row.update({"status": "PASS", "device_alias": "DEVICE_A", "clip_id": "CLIP_A",
                    "source_sha": FAKE_SHA, "layout": "popup", "input_mode": "video",
                    "source_language": "en", "model_ready": "YES", "first_translation": "YES",
                    "correction_behavior": "OFF", "evidence_reference": "PRIVATE_A",
                    "performance_csv": "perf.csv", "caption_json": "caption.json"})
        with (self.folder / "perf.csv").open("w", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=(
                "elapsed_s", "pss_kb", "cpu_percent", "battery_temp_c", "battery_level_percent"))
            writer.writeheader()
            for i in range(61):
                writer.writerow({"elapsed_s": i * 5, "pss_kb": 10000,
                                 "cpu_percent": 12, "battery_temp_c": 33, "battery_level_percent": 80})
        report = {"schema": "read-ux-00-v1", "status": "ANNOTATED_UNVERIFIED",
                  "source_sha": FAKE_SHA, "layout": "split", "session_id": row["session_id"],
                  "source_clip_ms": 300000, "capture_tail_ms": 30000,
                  "counts": {"annotated_cues": 4, "total_revisions": 0},
                  "lag_source_start_to_first_visible_ms": {"count": 4, "median": 1000, "p95": 2000},
                  "missing_rate_excluding_ambiguous": 0.1}
        (self.folder / "caption.json").write_text(json.dumps(report), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "source, session, layout"):
            verify_row(row, self.folder)
        report["layout"] = "popup"
        (self.folder / "caption.json").write_text(json.dumps(report), encoding="utf-8")
        result = verify_row(row, self.folder)
        self.assertEqual(result["cpu_median_percent"], 12)
        self.assertEqual(result["first_caption_lag_p95_ms"], 2000)


if __name__ == "__main__":
    unittest.main()
