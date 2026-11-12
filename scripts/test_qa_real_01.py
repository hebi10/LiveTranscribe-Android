import csv
import tempfile
import unittest
from pathlib import Path

import qa_real_01 as qa


class RealDeviceEvidenceAuditTests(unittest.TestCase):
    def test_template_never_asserts_unperformed_tests(self):
        with tempfile.TemporaryDirectory() as directory:
            filename = Path(directory) / "results.csv"
            qa.write_template(filename)
            rows = qa.read_results(filename)
            self.assertEqual(len(qa.REQUIRED_IDS), len(rows))
            self.assertEqual(set(qa.REQUIRED_IDS), {r["test_id"] for r in rows})
            self.assertTrue(all(r["status"] == "NOT RUN" for r in rows))
            self.assertFalse(qa.audit(rows)["humanReviewCandidate"])
            with self.assertRaisesRegex(ValueError, "overwrite"):
                qa.write_template(filename)

    def test_pass_requires_provenance_and_evidence(self):
        rows = [{"test_id": "DEV-01", "status": "PASS",
                 "attempt": "1", "abi": "arm64-v8a", "app_commit": "abc",
                 "evidence_reference": ""}]
        result = qa.audit(rows)
        self.assertFalse(result["humanReviewCandidate"])
        self.assertTrue(any("evidence" in e for e in result["validationErrors"]))

    def test_high_risk_cases_require_three_distinct_real_attempts(self):
        rows = []
        for scenario in qa.REQUIRED_IDS:
            for attempt in range(1, (4 if scenario in qa.REPEAT_IDS else 2)):
                rows.append({"test_id": scenario, "status": "PASS", "attempt": str(attempt),
                             "abi": "arm64-v8a", "app_commit": "same-source",
                             "evidence_reference": "restricted/test-evidence"})
        complete = qa.audit(rows)
        self.assertTrue(complete["humanReviewCandidate"])
        self.assertEqual("never_automatic_manual_review_required", complete["releaseApproval"])
        missing_repeat = [r for r in rows if not (r["test_id"] == "CORR-03" and r["attempt"] == "3")]
        incomplete = qa.audit(missing_repeat)
        self.assertFalse(incomplete["humanReviewCandidate"])
        self.assertIn("CORR-03", incomplete["pendingScenarioIds"])

    def test_failures_and_unknown_ids_never_approve(self):
        rows = [
            {"test_id": "DEV-10", "status": "FAIL", "attempt": "1"},
            {"test_id": "INV-99", "status": "PASS", "attempt": "1"}
        ]
        result = qa.audit(rows)
        self.assertFalse(result["humanReviewCandidate"])
        self.assertTrue(result["validationErrors"])

    def test_reader_rejects_wrong_header(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "wrong.csv"
            path.write_text("test_id,status\nDEV-01,PASS\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "Unexpected CSV fields"):
                qa.read_results(path)


if __name__ == "__main__":
    unittest.main()
