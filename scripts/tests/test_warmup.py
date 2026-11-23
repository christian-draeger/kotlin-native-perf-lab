import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "warmup.py"


class WarmupTest(unittest.TestCase):
    def test_requires_three_clean_windows_and_low_compilation_time(self):
        cases = (
            (105, 0, "1.20", "stable"),
            (112, 0, "1.20", "unstable"),
            (105, 1, "1.20", "unstable"),
            (105, 0, "1.21", "unstable"),
            (0, 0, "1.20", "unstable"),
        )
        with tempfile.TemporaryDirectory() as directory:
            paths = [Path(directory) / str(index) for index in range(7)]
            for rate, timeouts, compiler_time, expected in cases:
                with self.subTest(rate=rate, timeouts=timeouts, compiler_time=compiler_time):
                    for path, count in zip(paths[:3], (100, 101, rate)):
                        path.write_text(
                            f"perflab requests={count} duration_us=1000000 connect=0 "
                            f"read=0 write=0 timeout={timeouts if path == paths[2] else 0} http_errors=0\n"
                        )
                    for index, (path, time) in enumerate(zip(paths[3:], ("1.00", "1.05", "1.10", compiler_time))):
                        path.write_text(f"Timestamp Compiled Failed Time\n{10 * index + 2}.0 {50 + index} 0 {time}\n")
                    result = subprocess.check_output(
                        [sys.executable, SCRIPT, *paths[:3], "--compiler", *paths[3:]], text=True
                    )
                    self.assertEqual(result.split()[0], expected)
