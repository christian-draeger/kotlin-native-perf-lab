import importlib.util
import json
from pathlib import Path
import sys
from tempfile import TemporaryDirectory
import unittest


SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
spec = importlib.util.spec_from_file_location("benchmark_integrations", SCRIPTS / "benchmark-integrations.py")
benchmark = importlib.util.module_from_spec(spec)
spec.loader.exec_module(benchmark)


class K6TrialTest(unittest.TestCase):
    def test_records_matched_successful_arrivals_and_rejects_generator_drops(self):
        def counter(count):
            return {"values": {"count": count}}

        def trend(count):
            return {"values": {"count": count, "p(50)": 1, "p(95)": 2, "p(99)": 3}}

        sample = {
            "perflab_rates": {"json": 20, "catalog": 20},
            "metrics": {
                "http_reqs": counter(80), "iterations": counter(80),
                "perflab_success_duration": trend(80),
                "perflab_json_duration": trend(40), "perflab_catalog_duration": trend(40),
            },
        }
        with TemporaryDirectory() as temp:
            path = Path(temp) / "summary.json"
            path.write_text(json.dumps(sample))
            result = benchmark.read_k6_summary(path, offered=80, duration=2)
            self.assertEqual(result["valid"], 1)
            self.assertEqual(result["rps"], 40)
            self.assertEqual(result["p99_us"], 3000)
            self.assertEqual(json.loads(result["operations"])["catalog"], {"rps": 20, "p99": 3})

            sample["metrics"]["dropped_iterations"] = counter(1)
            path.write_text(json.dumps(sample))
            self.assertEqual(benchmark.read_k6_summary(path, offered=80, duration=2)["valid"], 0)

            del sample["metrics"]["dropped_iterations"]
            sample["metrics"]["perflab_catalog_duration"] = trend(35)
            path.write_text(json.dumps(sample))
            self.assertEqual(benchmark.read_k6_summary(path, offered=80, duration=2)["valid"], 0)

    def test_churn_requires_a_connection_setup_for_each_request(self):
        def metric(count):
            return {"values": {"count": count, "p(50)": 1, "p(95)": 2, "p(99)": 3}}

        sample = {
            "perflab_rates": {"json": 20},
            "metrics": {key: metric(40) for key in (
                "http_reqs", "iterations", "perflab_success_duration", "perflab_json_duration",
                "perflab_new_connections",
            )},
        }
        with TemporaryDirectory() as temp:
            path = Path(temp) / "summary.json"
            path.write_text(json.dumps(sample))
            result = benchmark.read_k6_summary(path, offered=40, duration=2, profile="churn")
            self.assertEqual((result["valid"], result["new_connections"]), (1, 40))

            sample["metrics"]["perflab_new_connections"] = metric(39)
            path.write_text(json.dumps(sample))
            self.assertEqual(benchmark.read_k6_summary(path, offered=40, duration=2, profile="churn")["valid"], 0)

    def test_overload_preserves_failures_and_unsent_arrivals(self):
        def metric(count):
            return {"values": {"count": count, "p(50)": 2, "p(95)": 20, "p(99)": 110}}

        sample = {
            "perflab_rates": {"json": 20},
            "metrics": {key: metric(count) for key, count in {
                "http_reqs": 35, "iterations": 35, "dropped_iterations": 5, "perflab_failed_responses": 2,
                "perflab_success_duration": 33, "perflab_json_duration": 33, "perflab_json_failed": 2,
                "perflab_success_within_100ms": 30, "perflab_timeouts": 1,
            }.items()},
        }
        with TemporaryDirectory() as temp:
            path = Path(temp) / "summary.json"
            path.write_text(json.dumps(sample))
            outcome = benchmark.read_k6_summary(path, offered=40, duration=2, profile="overload")
            self.assertEqual((outcome["valid"], outcome["accounted"], outcome["timeouts"]), (0, 1, 1))
            self.assertEqual((outcome["failed_responses"], outcome["dropped_iterations"]), (2, 5))
            self.assertEqual(outcome["within_budget_rps"], 15)

            sample["metrics"]["dropped_iterations"] = metric(2)
            path.write_text(json.dumps(sample))
            self.assertEqual(benchmark.read_k6_summary(path, offered=40, duration=2, profile="overload")["accounted"], 0)

    def test_capacity_keeps_budget_misses_separate_from_delivery_and_generator_pressure(self):
        def metric(count, p99=1):
            return {"values": {"count": count, "p(50)": 1, "p(95)": 1, "p(99)": p99}}

        rates = {name: 20 for name in benchmark.TRAFFIC_BUDGETS_MS}
        offered = sum(rates.values())
        sample = {
            "perflab_rates": rates,
            "metrics": {
                "http_reqs": metric(offered), "iterations": metric(offered),
                "perflab_success_duration": metric(offered),
                "perflab_success_within_operation_budget": metric(offered - 1),
                **{f"perflab_{name}_duration": metric(20, limit) for name, limit in benchmark.TRAFFIC_BUDGETS_MS.items()},
            },
        }
        with TemporaryDirectory() as temp:
            path = Path(temp) / "summary.json"
            path.write_text(json.dumps(sample))
            outcome = benchmark.read_k6_summary(path, offered, duration=1, profile="capacity")
            self.assertEqual((outcome["valid"], outcome["accounted"]), (1, 1))
            self.assertEqual(benchmark.read_k6_summary(path, offered, 1, "capacity", fresh_connections=True)["valid"], 0)
            sample["metrics"]["perflab_new_connections"] = metric(offered)
            path.write_text(json.dumps(sample))
            fresh = benchmark.read_k6_summary(path, offered, 1, "capacity", fresh_connections=True)
            self.assertEqual((fresh["valid"], fresh["new_connections"]), (1, offered))
            self.assertEqual(outcome["within_budget_rps"], offered - 1)
            self.assertTrue(benchmark.traffic_p99_within_budgets(outcome))
            self.assertTrue(benchmark.generator_headroom({**outcome, "load_cpu_equivalents": 1.6}, 2))
            self.assertFalse(benchmark.generator_headroom({**outcome, "load_cpu_equivalents": 1.61}, 2))
            self.assertFalse(benchmark.generator_headroom({**outcome, "load_cpu_equivalents": ""}, 2))
            self.assertFalse(benchmark.generator_headroom(
                {**outcome, "load_cpu_equivalents": 0.5, "dropped_iterations": 1}, 2,
            ))

            sample["metrics"]["perflab_catalog_duration"] = metric(20, p99=21)
            path.write_text(json.dumps(sample))
            outcome = benchmark.read_k6_summary(path, offered, duration=1, profile="capacity")
            self.assertEqual(outcome["valid"], 1)
            self.assertFalse(benchmark.traffic_p99_within_budgets(outcome))
