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
