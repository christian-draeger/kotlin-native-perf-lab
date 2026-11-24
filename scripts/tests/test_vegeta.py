import json
from pathlib import Path
import sys
from tempfile import TemporaryDirectory
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from parse_vegeta import read_report


class FixedLoadReportTest(unittest.TestCase):
    def test_requires_exact_status_and_planned_delivery(self):
        base = {"errors": [], "latencies": {"50th": 1000, "95th": 2000, "99th": 3000}}
        with TemporaryDirectory() as directory:
            path = Path(directory) / "report.json"
            for requests, codes, errors, valid in (
                (98, {"200": 98}, [], True),
                (102, {"200": 102}, [], True),
                (97, {"200": 97}, [], False),
                (103, {"200": 103}, [], False),
                (100, {"200": 99, "302": 1}, [], False),
                (100, {"200": 100}, ["read failed"], False),
                (0, {}, [], False),
            ):
                with self.subTest(requests=requests, codes=codes, errors=errors):
                    path.write_text(json.dumps(dict(base, requests=requests, status_codes=codes, errors=errors)))
                    result = read_report(path, 100)
                    self.assertEqual(result["valid"], valid)
                    self.assertEqual(result["ok"], codes.get("200", 0))
