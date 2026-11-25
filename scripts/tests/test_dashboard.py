import csv
from copy import deepcopy
import importlib.util
import json
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "build-dashboard.py"
spec = importlib.util.spec_from_file_location("build_dashboard", SCRIPT)
dashboard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(dashboard)
parser_spec = importlib.util.spec_from_file_location("parse_wrk", SCRIPT.with_name("parse_wrk.py"))
wrk = importlib.util.module_from_spec(parser_spec)
parser_spec.loader.exec_module(wrk)


class DashboardTest(unittest.TestCase):
    def test_traffic_keeps_unqualified_load_out_of_comparable_metrics(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            report = root / "results/20261124T221558Z-x86_64-mix"
            report.mkdir(parents=True)
            sha = "a" * 40
            (report / "environment.txt").write_text(
                "integration_experiment=traffic-capacity\nbenchmark_source=github-hosted-actions\n"
                f"git_worktree=clean\ngit_sha={sha}\nci_run_id=1234\nmachine=x86_64\n"
                "repetitions=2\ntraffic_target=API p99 within 20 ms\n"
            )
            path = report / "traffic.csv"
            base = {
                "variant": "ktserve", "offered_rate": 3200, "run": 1, "git_sha": sha,
                "valid": 1, "generator_qualified": 1, "slo_met": 1, "within_budget_rps": 3150,
                "failed_responses": 0, "dropped_iterations": 0, "rps": 3200,
                "p50_us": 100, "p95_us": 150, "p99_us": 200,
                "cpu_seconds_per_million_requests": 123, "peak_rss_kib": 21000,
            }
            invalid = {**base, "run": 2, "generator_qualified": 0, "slo_met": 0,
                       "dropped_iterations": 300, "within_budget_rps": 2800, "rps": 9000}
            with path.open("w", newline="") as output:
                writer = csv.DictWriter(output, fieldnames=base)
                writer.writeheader()
                writer.writerows([base, invalid, {**base, "variant": "rust-axum", "slo_met": 0}])
            run = dashboard.read_traffic_run(path, root, "archived-data")
            self.assertEqual(run["expected"], 2)
            group = run["tiers"][3200]["ktserve"]
            self.assertEqual((group["total"], group["valid"], group["generator_qualified"], group["met"]), (2, 1, 1, 1))
            self.assertEqual(group["dropped"], 300)
            self.assertEqual(group["on_time_rps"], 2975)
            self.assertEqual(group["stats"]["rps"]["median"], 3200)
            self.assertEqual(run["tiers"][3200]["rust-axum"]["met"], 0)

            with path.open("a", newline="") as output:
                csv.DictWriter(output, fieldnames=base).writerow(base)
            with self.assertRaisesRegex(ValueError, "duplicate traffic trial"):
                dashboard.read_traffic_run(path, root, "archived-data")

    def test_history_uses_valid_ktserve_trials_and_keeps_workloads_apart(self):
        parameters = {name: [value] for name, value in zip(dashboard.PARAMETERS, (16, 2, 10, 5))}
        ktserve = {
            "variant": "ktserve", "endpoint": "/health", "valid": 3, "total": 3,
            "stats": {"rps": {"median": 100, "min": 90, "max": 110}},
        }
        common = {
            "machine": "x86_64", "host": "AMD EPYC", "os": "Ubuntu 24.04",
            "runner": "ubuntu-24.04", "load_generator": "local HTTP/1.1",
            "note": {"kind": "Exploratory CI run"}, "dirty": False, "incomplete": False,
            "parameters": parameters, "groups": [ktserve],
        }
        runs = []
        for day, overrides in enumerate((
            {}, {}, {"machine": "aarch64"}, {"host": "Intel Xeon"},
            {"parameters": {**parameters, "connections": [64]}},
            {"incomplete": True}, {"dirty": True},
            {"groups": [{**ktserve, "valid": 2}]},
            {"groups": [{**ktserve, "variant": "rust-axum"}]},
            {"parameters": {**parameters, "connections": [16, 64]}},
        ), 1):
            run = deepcopy({**common, **overrides})
            runs.append({**run, "id": str(day), "date": f"2026-11-{day:02}"})
        profiles = dashboard.measurement_history(runs[::-1], variant="ktserve")
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

    def test_maps_archived_ids_without_changing_trial_data(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            rows = {
                "endpoint": "/json", "variant": "kotlin-reactor", "valid": "1",
                "rps": 101, "p50_us": 50, "p95_us": 100, "p99_us": 150,
                "cpu_seconds_per_million_requests": 10, "peak_rss_kib": 2048,
                "connections": 16, "threads": 2, "warmup_s": 10, "target_duration_s": 5,
            }
            runs = []
            for day, variant in ((22, "kotlin-reactor"), (23, "ktserve")):
                reports = root / f"results/202611{day}T221558Z"
                reports.mkdir(parents=True)
                (reports / "environment.txt").write_text(
                    'PRETTY_NAME="Ubuntu 24.04"\nmachine=x86_64\n'
                    'load_generator=local HTTP/1.1\nModel name: AMD EPYC\n'
                )
                path = reports / "results.csv"
                with path.open("w", newline="") as output:
                    writer = csv.DictWriter(output, fieldnames=rows)
                    writer.writeheader()
                    writer.writerow({**rows, "variant": variant})
                original = path.read_bytes()
                run = dashboard.read_run(path, {}, root, "archive-revision")
                self.assertEqual(path.read_bytes(), original)
                self.assertEqual(run["groups"][0]["variant"], "ktserve")
                self.assertEqual(run["groups"][0]["stats"]["rps"]["median"], 101)
                runs.append(run)
            history = dashboard.measurement_history(runs, variant="ktserve")
            self.assertEqual(len(history), 1)
            self.assertEqual([run["id"] for run in history[0]["runs"]], [run["id"] for run in runs])

        self.assertEqual(dashboard.canonical_variant("nativeserve-workers-2"), "ktserve-workers-2")
        self.assertEqual(dashboard.canonical_variant("spring-webflux-reactor-netty"), "spring-webflux-reactor-netty")

    def test_keeps_both_ktserve_worker_profiles_in_the_same_ranking(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            run = root / "results/20261125T120000Z-x86_64-json"
            run.mkdir(parents=True)
            (run / "environment.txt").write_text(
                "benchmark_suite=expanded\nbenchmark_endpoint=/json\n"
                "benchmark_source=github-hosted-actions\nci_run_id=5678\nmachine=x86_64\n"
            )
            profiles = json.loads((SCRIPT.parent / "expanded-server-workers.json").read_text())
            (run / "server-workers.json").write_text(json.dumps(profiles))
            base = {
                "endpoint": "/json", "variant": "ktserve", "valid": 1, "rps": 150,
                "p50_us": 50, "p95_us": 100, "p99_us": 200, "cpu_seconds_per_million_requests": 8,
                "peak_rss_kib": 20_000, "connections": 16, "threads": 2, "warmup_s": 10, "target_duration_s": 5,
            }
            path = run / "results.csv"
            with path.open("w", newline="") as output:
                writer = csv.DictWriter(output, fieldnames=base)
                writer.writeheader()
                writer.writerows([base, {**base, "variant": "ktserve-workers-2", "rps": 240}])

            ranking = dashboard.read_run(path, {}, root, "archive")
            self.assertEqual((ranking["suite"], ranking["valid"]), ("expanded", 2))
            self.assertEqual(
                {group["variant"]: group["stats"]["rps"]["median"] for group in ranking["groups"]},
                {"ktserve": 150, "ktserve-workers-2": 240},
            )
            self.assertEqual(
                {group["variant"]: group["worker_profile"] for group in ranking["groups"]},
                {variant: profiles[variant] for variant in ("ktserve", "ktserve-workers-2")},
            )

    def test_expanded_worker_profiles_cover_all_launchers(self):
        launchers = subprocess.check_output(
            ["bash", "-c", 'BENCHMARK_SUITE=expanded; source scripts/benchmark-variants.sh; printf "%s\\n" "${variants[@]}"'],
            cwd=SCRIPT.parent.parent, text=True,
        ).splitlines()
        profiles = json.loads((SCRIPT.parent / "expanded-server-workers.json").read_text())
        self.assertEqual(len(launchers), len(profiles))
        self.assertEqual(set(launchers), set(profiles))

    def test_eager_ranking_records_effective_count_without_relabeling_fixed_modes(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            run = root / "results/20261122T221558Z"
            run.mkdir(parents=True)
            (run / "environment.txt").write_text("benchmark_suite=expanded\nmachine=aarch64\n")
            worker_profiles = (SCRIPT.parent / "expanded-server-workers.json").read_text()
            (run / "server-workers.json").write_text(worker_profiles)
            path = run / "results.csv"
            row = {
                "variant": "ktserve", "endpoint": "/json", "valid": 1, "rps": 100,
                "p50_us": 50, "p95_us": 100, "p99_us": 150,
                "cpu_seconds_per_million_requests": 10, "peak_rss_kib": 2048,
                "connections": 16, "threads": 2, "warmup_s": 10, "target_duration_s": 5,
                "effective_workers": "",
            }
            for count, setup in ((1, "1 epoll worker · CPU budget fallback"),
                                 (2, "2 epoll workers · shared listener")):
                with self.subTest(count=count):
                    with path.open("w", newline="") as output:
                        writer = csv.DictWriter(output, fieldnames=row)
                        writer.writeheader()
                        writer.writerows([row, {**row, "variant": "ktserve-eager", "effective_workers": count}])
                    groups = {group["variant"]: group for group in dashboard.read_run(path, {}, root, "test")["groups"]}
                    self.assertEqual(groups["ktserve"]["worker_profile"]["setup"], "1 epoll worker")
                    self.assertEqual(groups["ktserve-eager"]["worker_profile"]["setup"], setup)
                    self.assertEqual(groups["ktserve-eager"]["worker_profile"]["effective_workers"], [count])

    def test_eager_worker_count_requires_one_parseable_startup_record(self):
        with TemporaryDirectory() as directory:
            log = Path(directory) / "startup.log"
            log.write_text("ktServe worker_policy=eager workers=2\nktServe backend=epoll tcp=ipv4 workers=2\n")
            self.assertEqual(wrk.read_eager_worker_count(log), 2)
            for text in ("", "ktServe worker_policy=eager workers=3\n",
                         "ktServe worker_policy=eager workers=1\nktServe worker_policy=eager workers=2\n"):
                with self.subTest(text=text):
                    log.write_text(text)
                    with self.assertRaisesRegex(ValueError, "Expected one Eager startup record"):
                        wrk.read_eager_worker_count(log)

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

            with (health / "environment.txt").open("a") as output:
                output.write("integration_family=application-mix\nload_tool=k6\n")
            app_mix = dashboard.read_run(health / "results.csv", {}, root, "archive-revision")
            self.assertEqual(app_mix["integration"], "application-mix")
            self.assertEqual(app_mix["load_tool"], "k6")
            self.assertEqual(app_mix["note"]["label"], "Standalone application mix · Linux ARM64")
