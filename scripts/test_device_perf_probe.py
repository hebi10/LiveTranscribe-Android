import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location("device_perf_probe", Path(__file__).with_name("device_perf_probe.py"))
probe = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(probe)


class DevicePerfProbeTests(unittest.TestCase):
    def test_pid_requires_a_single_process(self):
        self.assertEqual(probe.parse_pid("1234\n"), 1234)
        self.assertIsNone(probe.parse_pid("1234 5678"))
        self.assertIsNone(probe.parse_pid(""))

    def test_memory_formats(self):
        self.assertEqual(probe.parse_meminfo(" TOTAL PSS: 1,234 TOTAL RSS: 5"), 1234)
        self.assertEqual(probe.parse_meminfo(" TOTAL  45234  100  200"), 45234)
        self.assertIsNone(probe.parse_meminfo("Unable to retrieve meminfo"))

    def test_cpu_is_exact_pid(self):
        sample = " 5.6% 1234/com.example: 4% user + 1.6% kernel\n3.2% 999/com.other:"
        self.assertEqual(probe.parse_cpuinfo(sample, 1234), 5.6)
        self.assertIsNone(probe.parse_cpuinfo(sample, 12))
        self.assertIsNone(probe.parse_cpuinfo(sample, None))

    def test_battery(self):
        self.assertEqual(probe.parse_battery(" temperature: 355\n level: 72"), (35.5, 72))
        self.assertEqual(probe.parse_battery("powered: false"), (None, None))


if __name__ == "__main__":
    unittest.main()
