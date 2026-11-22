import csv
import importlib.util
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "build-dashboard.py"
spec = importlib.util.spec_from_file_location("build_dashboard", SCRIPT)
dashboard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(dashboard)


class DashboardTest(unittest.TestCase):
    def test_separates_groups_and_excludes_invalid_trials(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            run = root / "results/20261122T221558Z"
            run.mkdir(parents=True)
            (run / "environment.txt").write_text(
                'PRETTY_NAME="Ubuntu 24.04 LTS"\nmachine=x86_64\nload_generator=local HTTP/1.1\n'
            )
            common = {
                "endpoint": "/health", "variant": "ktor-native", "valid": "1",
                "rps": 100, "p50_us": 50, "p95_us": 100, "p99_us": 150,
                "cpu_seconds_per_million_requests": 10, "peak_rss_kib": 2048,
                "connections": 16, "threads": 2, "warmup_s": 10, "target_duration_s": 5,
            }
            with (run / "results.csv").open("w", newline="") as output:
                writer = csv.DictWriter(output, fieldnames=common)
                writer.writeheader()
                writer.writerows([
                    common, {**common, "rps": 200, "p99_us": 250},
                    {**common, "valid": "0", "rps": 10000},
                    {**common, "variant": "rust-axum", "valid": "0"},
                    {**common, "endpoint": "/json", "rps": 120},
                ])
            with patch.object(dashboard, "ROOT", root):
                data = dashboard.read_run(run / "results.csv", {})
            native, failed, json_group = data["groups"]
            self.assertEqual(data["valid"], 3)
            self.assertEqual(data["total"], 5)
            self.assertTrue(data["local"])
            self.assertEqual(data["os"], "Ubuntu 24.04 LTS")
            self.assertEqual(data["parameters"]["connections"], [16])
            self.assertEqual(native["stats"]["rps"], {"median": 150, "min": 100, "max": 200})
            self.assertEqual(native["stats"]["p99"]["median"], 0.2)
            self.assertEqual(native["stats"]["rss"]["median"], 2)
            self.assertEqual(failed["valid"], 0)
            self.assertIsNone(failed["stats"])
            self.assertEqual(json_group["stats"]["rps"]["median"], 120)
