import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "warmup.py"


class WarmupTest(unittest.TestCase):
    def test_requires_three_clean_windows_and_quiet_compiler(self):
        cases = (
            (105, 0, 50, "stable"),
            (112, 0, 50, "unstable"),
            (105, 1, 50, "unstable"),
            (105, 0, 51, "unstable"),
            (0, 0, 50, "unstable"),
        )
        with tempfile.TemporaryDirectory() as directory:
            paths = [Path(directory) / str(index) for index in range(6)]
            for rate, timeouts, compiled, expected in cases:
                with self.subTest(rate=rate, timeouts=timeouts, compiled=compiled):
                    for path, count in zip(paths[:3], (100, 101, rate)):
                        path.write_text(
                            f"perflab requests={count} duration_us=1000000 connect=0 "
                            f"read=0 write=0 timeout={timeouts if path == paths[2] else 0} http_errors=0\n"
                        )
                    for path, count in zip(paths[3:], (50, 50, compiled)):
                        path.write_text(f"Time Compiled Failed\n1.23 {count} 0\n")
                    result = subprocess.check_output(
                        [sys.executable, SCRIPT, *paths[:3], "--compiler", *paths[3:]], text=True
                    )
                    self.assertEqual(result.split()[0], expected)
