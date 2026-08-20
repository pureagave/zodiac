"""The edge box's $ZVER identity derivation (FLEET-1). The load-bearing behaviour
is the *fallbacks*: every git failure must degrade to unknown/dirty, never to a
confident-but-wrong current build. The clean-path emit is checked by the golden
suites; this pins the failure modes and the cadence.
"""

import os
import tempfile
import unittest

from zvision import __version__
from zvision.version_protocol import parse
from zvision.version_report import VersionScheduler, self_version


def _git(rev_parse=None, status=None, show=None):
    """A fake GitRunner keyed on the subcommand, returning canned output."""
    table = {"rev-parse": rev_parse, "status": status, "show": show}

    def run(args, cwd):
        return table.get(args[0])

    return run


class SelfVersionTest(unittest.TestCase):
    def test_clean_build_from_good_git(self):
        v = self_version(
            run_git=_git(rev_parse="8f531e18a", status="", show="1691900000"),
            hostname="zvision",
            machine_id_path="/nonexistent",
        )
        self.assertEqual("zvision", v.name)
        self.assertEqual(__version__, v.base)
        self.assertEqual("8f531e18a", v.sha)
        self.assertFalse(v.dirty)
        self.assertEqual(1_691_900_000, v.epoch)

    def test_git_absent_fails_toward_unknown_and_dirty(self):
        v = self_version(run_git=_git(), hostname="zvision", machine_id_path="/nonexistent")
        self.assertEqual("unknown", v.sha)
        self.assertTrue(v.dirty)  # git failed -> dirty
        self.assertEqual(0, v.epoch)
        # And it must still round-trip on the wire (rendered UNKNOWN, not broken).
        self.assertIsNotNone(parse(_build(v)))

    def test_dirty_tree_when_porcelain_is_nonempty(self):
        v = self_version(
            run_git=_git(rev_parse="8f531e18a", status=" M zvision/app.py", show="1691900000"),
            hostname="zvision",
            machine_id_path="/nonexistent",
        )
        self.assertTrue(v.dirty)

    def test_garbage_sha_becomes_unknown(self):
        v = self_version(
            run_git=_git(rev_parse="not-a-sha", status="", show="1691900000"),
            hostname="zvision",
            machine_id_path="/nonexistent",
        )
        self.assertEqual("unknown", v.sha)

    def test_node_is_last6_of_machine_id_uppercased(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "machine-id")
            with open(path, "w") as f:
                f.write("0123456789abcdef0123456789abcdef\n")
            v = self_version(run_git=_git(), hostname="zvision", machine_id_path=path)
        # last 6 of "...0123456789abcdef", uppercased hex.
        self.assertEqual("ABCDEF", v.node)

    def test_node_falls_back_to_hostname_without_machine_id(self):
        v = self_version(run_git=_git(), hostname="zvision-box-7", machine_id_path="/nonexistent")
        # Non-[A-Z0-9] stripped, uppercased, last 6 -> "...BOX7" tail.
        self.assertEqual("NBOX7", v.node[-5:])
        self.assertTrue(v.node.isupper())

    def test_built_identity_round_trips(self):
        v = self_version(
            run_git=_git(rev_parse="0a1b2c3d4", status="", show="1690000000"),
            hostname="zvision",
            machine_id_path="/nonexistent",
        )
        back = parse(_build(v))
        self.assertIsNotNone(back)
        self.assertEqual(v, back)


class VersionSchedulerTest(unittest.TestCase):
    def test_first_due_is_true_then_gated_by_period(self):
        t = {"now": 100.0}
        s = VersionScheduler(period=10.0, clock=lambda: t["now"])
        self.assertTrue(s.due(), "the first announcement fires immediately")
        self.assertFalse(s.due(), "not due again within the period")
        t["now"] = 109.9
        self.assertFalse(s.due(), "still within the period")
        t["now"] = 110.0
        self.assertTrue(s.due(), "due once the period has elapsed")


def _build(v):
    from zvision.version_protocol import build

    return build(v)


if __name__ == "__main__":
    unittest.main()
