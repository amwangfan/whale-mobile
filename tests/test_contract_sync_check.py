"""Offline coverage of the V2 drift-check CLI, using only the standard library."""
import importlib.util
from pathlib import Path
import tempfile
import unittest


spec = importlib.util.spec_from_file_location(
    "contract_sync_check", Path(__file__).resolve().parents[1] / "scripts/contract_sync_check.py")
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


class ContractSyncTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.a, self.b = (Path(self.temp.name) / name for name in ("a", "b"))
        for root in (self.a, self.b):
            base = root / "contracts/v2"
            base.mkdir(parents=True)
            for name in ("PROTOCOL.md", "messages/a.schema.json", "objects/a.schema.json",
                         "examples/a.json", "objects/examples/a.json"):
                path = base / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("{}", encoding="utf-8")

    def check(self):
        return checker.main(["--a", str(self.a), "--b", str(self.b)])

    def test_complete_trees_match(self):
        self.assertEqual(self.check(), 0)
        self.assertEqual(checker.compare(self.a, self.b), ([], 5))

    def test_object_example_drift(self):
        (self.b / "contracts/v2/objects/examples/a.json").write_text('{"changed":true}')
        self.assertEqual(self.check(), 1)

    def test_identical_incomplete_trees_fail(self):
        for root in (self.a, self.b):
            (root / "contracts/v2/objects/examples/a.json").unlink()
        self.assertEqual(self.check(), 2)

    def test_missing_file_is_drift(self):
        (self.b / "contracts/v2/messages/a.schema.json").unlink()
        self.assertEqual(self.check(), 1)
