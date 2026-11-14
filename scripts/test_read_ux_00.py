import csv
import importlib.util
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("read_ux_00.py")
spec = importlib.util.spec_from_file_location("read_ux_00", SCRIPT)
ux = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ux)
SHA = "a" * 40


class ReadUx00Tests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / "events.csv"
        ux.create_template(self.path)

    def record(self, **kwargs):
        defaults = {
            "cue_id": "C001", "source_start_ms": "1000", "source_end_ms": "2500",
            "outcome": "MATCHED", "first_visible_ms": "3500", "settled_ms": "",
            "hidden_ms": "6000", "display_chars": "18", "max_lines": "2",
            "revisions": "0", "duplicates": "0"
        }
        defaults.update(kwargs)
        with self.path.open("a", newline="", encoding="utf-8-sig") as output:
            csv.DictWriter(output, fieldnames=ux.COLUMNS).writerow(defaults)

    def summary(self):
        return ux.summarize(ux.parse_rows(self.path), session_id="pop-en-01", source_sha=SHA, layout="popup")

    def test_blank_template_remains_not_run(self):
        self.assertEqual(self.summary()["status"], "NOT_RUN")
        self.assertIsNone(self.summary()["lag_source_start_to_first_visible_ms"]["median"])
        self.assertFalse(self.summary()["release_approved"])

    def test_matched_latency_duration_and_no_automatic_approval(self):
        self.record()
        result = self.summary()
        self.assertEqual(result["status"], "ANNOTATED_UNVERIFIED")
        self.assertEqual(result["lag_source_start_to_first_visible_ms"]["median"], 2500)
        self.assertEqual(result["lag_source_end_to_first_visible_ms"]["median"], 1000)
        self.assertEqual(result["caption_visible_duration_ms"]["median"], 2500)
        self.assertAlmostEqual(result["display_chars_per_visible_second"]["median"], 7.2)
        self.assertFalse(result["actual_physical_device_proven"])

    def test_revisions_and_flags(self):
        self.record(settled_ms="4500", revisions="2", display_chars="50", max_lines="3")
        result = self.summary()
        self.assertEqual(result["counts"]["revised_cues"], 1)
        self.assertEqual(result["time_until_last_rewrite_ms"]["median"], 1000)
        self.assertEqual(result["review_flags"]["display_above_12_cps"], 1)
        self.assertEqual(result["review_flags"]["more_than_two_lines"], 1)

    def test_missing_and_ambiguous_excluded_from_latency(self):
        self.record()
        self.record(cue_id="C002", source_start_ms="7000", source_end_ms="9000", outcome="MISSING",
                    first_visible_ms="", hidden_ms="", display_chars="", max_lines="", revisions="", duplicates="")
        self.record(cue_id="C003", source_start_ms="10000", source_end_ms="12000", outcome="AMBIGUOUS",
                    first_visible_ms="", hidden_ms="", display_chars="", max_lines="", revisions="", duplicates="")
        result = self.summary()
        self.assertEqual(result["missing_rate_excluding_ambiguous"], .5)
        self.assertEqual(result["lag_source_start_to_first_visible_ms"]["count"], 1)

    def test_hanging_caption_not_counted_as_zero_reading_time(self):
        self.record(hidden_ms="")
        result = self.summary()
        self.assertEqual(result["counts"]["matched_without_hide_timestamp"], 1)
        self.assertIsNone(result["display_chars_per_visible_second"]["median"])

    def test_rejects_invalid_alignment_and_duplicates(self):
        self.record(first_visible_ms="500")
        with self.assertRaisesRegex(ValueError, "outside capture"):
            ux.parse_rows(self.path)
        self.path.unlink()
        ux.create_template(self.path)
        self.record()
        self.record()
        with self.assertRaisesRegex(ValueError, "duplicate cue_id"):
            ux.parse_rows(self.path)

    def test_rejects_partial_captions_and_fake_revision(self):
        self.record(revisions="1", settled_ms="")
        with self.assertRaisesRegex(ValueError, "needs settled_ms"):
            ux.parse_rows(self.path)
        self.path.unlink()
        ux.create_template(self.path)
        self.record(outcome="MISSING", first_visible_ms="", settled_ms="", hidden_ms="",
                    display_chars="", max_lines="", revisions="", duplicates="1")
        with self.assertRaisesRegex(ValueError, "must not invent subtitle"):
            ux.parse_rows(self.path)

    def test_explicit_five_minute_window_and_negative_end_lag(self):
        self.record(source_start_ms="297000", source_end_ms="300000",
                    first_visible_ms="299000", hidden_ms="305000")
        result = self.summary()
        self.assertEqual(result["lag_source_end_to_first_visible_ms"]["median"], -1000)
        self.path.unlink()
        ux.create_template(self.path)
        self.record(source_start_ms="300000", source_end_ms="301000")
        with self.assertRaisesRegex(ValueError, "five-minute"):
            ux.parse_rows(self.path)

    def test_csv_schema_and_session_validation(self):
        with self.assertRaisesRegex(ValueError, "commit SHA"):
            ux.summarize([], session_id="pop-01", source_sha="bad", layout="popup")
        self.path.write_text("cue_id,subtitle_text\nC001,secret\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Unrecognized CSV"):
            ux.parse_rows(self.path)

    def test_no_overwrite_template(self):
        with self.assertRaises(FileExistsError):
            ux.create_template(self.path)


if __name__ == "__main__":
    unittest.main()
