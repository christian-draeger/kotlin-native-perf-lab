import csv
from copy import deepcopy
import importlib.util
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "build-dashboard.py"
spec = importlib.util.spec_from_file_location("build_dashboard", SCRIPT)
dashboard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(dashboard)


class DashboardTest(unittest.TestCase):
    def test_history_uses_valid_reactor_trials_and_keeps_workloads_apart(self):
        parameters = {name: [value] for name, value in zip(dashboard.PARAMETERS, (16, 2, 10, 5))}
        reactor = {
            "variant": "kotlin-reactor", "endpoint": "/health", "valid": 3, "total": 3,
            "stats": {"rps": {"median": 100, "min": 90, "max": 110}},
        }
        common = {
            "machine": "x86_64", "host": "AMD EPYC", "os": "Ubuntu 24.04",
            "runner": "ubuntu-24.04", "load_generator": "local HTTP/1.1",
            "note": {"kind": "Exploratory CI run"}, "dirty": False, "incomplete": False,
            "parameters": parameters, "groups": [reactor],
        }
        runs = []
        for day, overrides in enumerate((
            {}, {}, {"machine": "aarch64"}, {"host": "Intel Xeon"},
            {"parameters": {**parameters, "connections": [64]}},
            {"incomplete": True}, {"dirty": True},
            {"groups": [{**reactor, "valid": 2}]},
            {"groups": [{**reactor, "variant": "rust-axum"}]},
            {"parameters": {**parameters, "connections": [16, 64]}},
        ), 1):
            run = deepcopy({**common, **overrides})
            runs.append({**run, "id": str(day), "date": f"2026-11-{day:02}"})
        profiles = dashboard.measurement_history(runs[::-1], variant="kotlin-reactor")
        self.assertEqual(len(profiles), 4)
        self.assertEqual(
            [[entry["id"] for entry in profile["runs"]] for profile in profiles],
            [["5"], ["4"], ["3"], ["1", "2"]],
        )
        self.assertEqual(profiles[-1]["runs"][0]["endpoints"], ["/health"])
        framework_profiles = dashboard.measurement_history(runs[::-1])
        self.assertEqual(
            [[entry["id"] for entry in profile["runs"]] for profile in framework_profiles],
            [["1", "2", "9"], ["5"], ["4"], ["3"]],
        )

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
            data = dashboard.read_run(run / "results.csv", {}, root, "source-revision")
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

            with (run / "environment.txt").open("a") as output:
                output.write(
                    "Model name: AMD EPYC\nbenchmark_source=github-hosted-actions\n"
                    "ci_run_id=1234\nbenchmark_outcome=failure\n"
                )
            ci = dashboard.read_run(run / "results.csv", {}, root, "archive-revision")
            self.assertNotEqual(ci["id"], data["id"])
            self.assertEqual(ci["note"]["kind"], "Exploratory CI run")
            self.assertEqual(ci["note"]["label"], "GitHub Actions · Linux x86_64")
            self.assertEqual(ci["note"]["host"], "AMD EPYC")
            self.assertEqual(ci["ci_run_id"], "1234")
            self.assertEqual(ci["revision"], "archive-revision")
            self.assertTrue(ci["incomplete"])

            arm = run.with_name(run.name + "-aarch64")
            run.rename(arm)
            environment = arm / "environment.txt"
            environment.write_text(environment.read_text().replace("machine=x86_64", "machine=aarch64"))
            arm_series = dashboard.read_run(arm / "results.csv", {}, root, "archive-revision")
            self.assertEqual(arm_series["date"], ci["date"])
            self.assertNotEqual(arm_series["id"], ci["id"])
            self.assertEqual(arm_series["note"]["label"], "GitHub Actions · Linux ARM64")

            health = arm.with_name(arm.name + "-health")
            arm.rename(health)
            with (health / "environment.txt").open("a") as output:
                output.write("benchmark_endpoint=/health\nbenchmark_suite=core\nci_run_attempt=2\n")
            health_series = dashboard.read_run(health / "results.csv", {}, root, "archive-revision")
            self.assertEqual(health_series["scope"], "/health")
            self.assertEqual(health_series["ci_run_attempt"], "2")
            self.assertEqual(health_series["date"], arm_series["date"])
            self.assertNotEqual(health_series["id"], arm_series["id"])
            self.assertEqual(health_series["note"]["label"], "GitHub Actions · Linux ARM64")
            self.assertEqual(health_series["suite"], "core")

            with (health / "environment.txt").open("a") as output:
                output.write("benchmark_suite=integration\nintegration_family=ktor-jvm\n")
            integration = dashboard.read_run(health / "results.csv", {}, root, "archive-revision")
            self.assertEqual(integration["integration"], "ktor-jvm")
            self.assertEqual(integration["note"]["label"], "Ktor JVM · Linux ARM64")
