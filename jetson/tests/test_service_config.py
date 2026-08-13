"""The systemd unit and installer are configuration the box bets its life on,
and configuration with no test is where this project's real failures have lived
(the OLA installer wrote plugin config to a directory olad never reads — total,
silent failure). These tests parse the actual shipped files and pin the
decisions that matter operationally, so a well-meaning edit can't quietly
reintroduce a known failure mode.
"""

import os
import re
import unittest

_HERE = os.path.dirname(os.path.abspath(__file__))
_UNIT = os.path.join(_HERE, "..", "systemd", "zvision.service")
_DECK_UNIT = os.path.join(_HERE, "..", "systemd", "zodiac-deck.service")
_INSTALL = os.path.join(_HERE, "..", "scripts", "install.sh")


def _parse_unit(path):
    """Minimal systemd-unit parse: {section: [(key, value), ...]}."""
    sections = {}
    current = None
    with open(path, encoding="utf-8") as fh:
        for raw in fh:
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            m = re.match(r"\[(.+)\]$", line)
            if m:
                current = m.group(1)
                sections.setdefault(current, [])
                continue
            key, _, value = line.partition("=")
            sections.setdefault(current, []).append((key.strip(), value.strip()))
    return sections


class ServiceUnitTest(unittest.TestCase):
    def setUp(self):
        self.sections = _parse_unit(_UNIT)

    def _values(self, section, key):
        return [v for k, v in self.sections.get(section, []) if k == key]

    def test_a_missing_config_file_fails_the_start_instead_of_going_fake(self):
        # With no ZVISION_ARGS the CLI defaults to --source fake: three
        # synthetic contacts, one a recurring phantom collision, on the
        # driver's HUD all night — indistinguishable on the wire from real
        # people. EnvironmentFile with a leading "-" makes a missing config
        # exactly that silent. It must be required, so the unit fails loudly.
        env_files = self._values("Service", "EnvironmentFile")
        self.assertTrue(env_files, "unit must load /etc/default/zvision")
        for value in env_files:
            self.assertFalse(
                value.startswith("-"),
                "EnvironmentFile is optional ('-' prefix): a deleted config would "
                "silently broadcast fake threats instead of failing the unit",
            )

    def test_retry_forever_is_declared_where_systemd_actually_reads_it(self):
        # StartLimitIntervalSec under [Service] is ignored with only a log
        # warning — which silently re-enables the default rate limit and lets
        # the unit give up on a camera that was just slow to enumerate.
        unit_keys = [k for k, _ in self.sections.get("Unit", [])]
        service_keys = [k for k, _ in self.sections.get("Service", [])]
        self.assertIn("StartLimitIntervalSec", unit_keys)
        self.assertNotIn("StartLimitIntervalSec", service_keys)
        self.assertEqual(["0"], self._values("Unit", "StartLimitIntervalSec"))

    def test_the_service_restarts_on_crash(self):
        self.assertEqual(["always"], self._values("Service", "Restart"))

    def test_the_service_runs_zvision_with_the_configured_args(self):
        execs = self._values("Service", "ExecStart")
        self.assertEqual(1, len(execs))
        self.assertIn("-m zvision", execs[0])
        self.assertIn("$ZVISION_ARGS", execs[0])

    def test_the_service_waits_for_the_vehicle_network(self):
        # Without this the first frames go out before a route exists and the
        # log shows "-> 0 targets" until something else kicks the socket.
        self.assertIn("network-online.target", self._values("Unit", "After"))
        self.assertIn("network-online.target", self._values("Unit", "Wants"))

    def test_the_working_directory_is_the_deployed_checkout(self):
        # `python3 -m zvision` resolves the package from the cwd; if this
        # drifts from where install.sh/DEPLOY.md put the code, the service
        # runs a stale or missing copy.
        self.assertEqual(["/opt/zodiac/jetson"], self._values("Service", "WorkingDirectory"))

    def test_the_crash_failsafe_derives_its_universe_from_zvision_args(self):
        # A UNIVERSE=1 deploy would otherwise have this fail-safe zero an
        # empty universe 0 while universe 1 stays hot -- see dmxpark.py's
        # --from-args-env. --default-dmx none matches this CLI's own
        # argparse default, so the park is skipped when ZVISION_ARGS doesn't
        # ask for --dmx ola (the split single-writer layout, DECK.md §3).
        execs = self._values("Service", "ExecStopPost")
        self.assertEqual(1, len(execs))
        self.assertIn("zvision.dmxpark", execs[0])
        self.assertIn("--from-args-env ZVISION_ARGS", execs[0])
        self.assertIn("--default-dmx none", execs[0])


class DeckServiceUnitTest(unittest.TestCase):
    def setUp(self):
        self.sections = _parse_unit(_DECK_UNIT)

    def _values(self, section, key):
        return [v for k, v in self.sections.get(section, []) if k == key]

    def test_the_crash_failsafe_derives_its_universe_from_zdeck_args(self):
        # --default-dmx ola matches zdeck's own --dmx default (app.py), so
        # absent an explicit --dmx in ZDECK_ARGS the deck IS treated as the
        # universe's owner and still parks on a crash/kill.
        execs = self._values("Service", "ExecStopPost")
        self.assertEqual(1, len(execs))
        self.assertIn("zvision.dmxpark", execs[0])
        self.assertIn("--from-args-env ZDECK_ARGS", execs[0])
        self.assertIn("--default-dmx ola", execs[0])


class InstallerTest(unittest.TestCase):
    def setUp(self):
        with open(_INSTALL, encoding="utf-8") as fh:
            self.script = fh.read()

    def test_config_is_written_before_the_service_is_started(self):
        # The unit refuses to start without /etc/default/zvision (see above),
        # so the installer must create it before enabling/restarting — the
        # other order bricks a fresh install with a failed unit.
        write_at = self.script.index("/etc/default/zvision")
        restart_at = self.script.index("systemctl restart zvision")
        self.assertLess(write_at, restart_at)

    def test_installer_survives_running_from_the_deployed_checkout(self):
        # DEPLOY.md §3: the repo is cloned directly at /opt/zodiac, so SRC and
        # DEST are the same path. An unconditional cp aborts on "identical"
        # files, and with set -e that killed the install before the systemd
        # unit was written. The copy must be guarded.
        self.assertIn('"${SRC}" != "${DEST}"', self.script)


if __name__ == "__main__":
    unittest.main()
