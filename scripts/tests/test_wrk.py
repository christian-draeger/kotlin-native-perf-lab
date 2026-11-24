from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from parse_wrk import read_load_timing


class LoadTimingTest(unittest.TestCase):
    def test_existing_logs_and_gnu_time_logs(self):
        with TemporaryDirectory() as directory:
            log = Path(directory) / "wrk.log"
            log.write_text("perflab requests=100\n")
            self.assertEqual(read_load_timing(log), {
                "load_cpu_seconds": "", "load_peak_rss_kib": "", "load_cpu_equivalents": "",
            })

            with log.open("a") as file:
                file.write("perflab_load elapsed_s=4.01 user_s=5.11 system_s=1.51 peak_rss_kib=1234\n")
            self.assertEqual(read_load_timing(log), {
                "load_cpu_seconds": 6.62, "load_peak_rss_kib": 1234, "load_cpu_equivalents": 1.651,
            })
