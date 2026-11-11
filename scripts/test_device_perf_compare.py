import importlib.util
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("perf_compare", Path(__file__).with_name("device_perf_compare.py"))
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


class PerformanceComparisonTests(unittest.TestCase):
    def test_percentile_and_missing_samples(self):
        self.assertEqual(module.metric([None])["count"], 0)
        self.assertIsNone(module.metric([])["p95"])
        self.assertEqual(module.metric(range(1, 21))["p95"], 19)

    def test_invalid_input_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp, "in.csv")
            path.write_text("elapsed_s,pss_kb,cpu_percent,battery_temp_c,battery_level_percent\n"
                            "5,300,2,33,90\n0,310,3,34,89\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "unordered"):
                module.read_run(path)

    def test_comparison_never_claims_improvement(self):
        def row(t, pss, cpu, temp):
            return {"elapsed_s": float(t), "pss_kb": float(pss),
                    "cpu_percent": float(cpu), "battery_temp_c": float(temp),
                    "battery_level_percent": 70.0}
        baseline = [row(i * 5, 100 + i * 10, 12, 32) for i in range(4)]
        candidate = [row(i * 5, 100 + i * 5, 10, 31) for i in range(4)]
        report = module.compare(baseline, candidate)
        self.assertTrue(report["comparable_duration_and_samples"])
        self.assertEqual(report["deltas"]["cpu_percent_median_delta"], -2)
        self.assertFalse(report["performance_improvement_verified"])

    def test_not_comparable_without_valid_data(self):
        base = [{"elapsed_s": 0., "pss_kb": None, "cpu_percent": None, "battery_temp_c": None,
                 "battery_level_percent": None}]
        self.assertFalse(module.compare(base, base)["comparable_duration_and_samples"])


if __name__ == "__main__":
    unittest.main()
