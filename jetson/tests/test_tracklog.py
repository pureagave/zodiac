import os
import tempfile
import unittest

from zvision.tracklog import CSV_HEADER, Fix, TrackWriter, parse_fix


def nmea(body):
    checksum = 0
    for char in body:
        checksum ^= ord(char)
    return f"${body}*{checksum:02X}"


UTC = "2026-08-30T14:03:21Z"


class ParseFixTest(unittest.TestCase):
    def test_parses_a_gga_position(self):
        line = nmea("GPGGA,140321,4047.2178,N,11912.1804,W,1,09,0.9,1190.0,M,-24.0,M,,")
        fix = parse_fix(line, UTC)
        self.assertIsNotNone(fix)
        self.assertAlmostEqual(fix.lat, 40.78696, places=4)
        self.assertAlmostEqual(fix.lon, -119.20301, places=4)
        self.assertEqual(fix.fix_quality, 1)
        self.assertEqual(fix.sats, 9)

    def test_parses_an_rmc_with_speed_and_course(self):
        line = nmea("GPRMC,140321,A,4047.2178,N,11912.1804,W,10.0,087.3,300826,,,A")
        fix = parse_fix(line, UTC)
        self.assertIsNotNone(fix)
        self.assertAlmostEqual(fix.speed_kph, 18.52, places=2)
        self.assertAlmostEqual(fix.heading_deg, 87.3, places=1)

    def test_a_receiver_with_no_sky_records_nothing(self):
        # The normal cold-start case, and the one that must not invent a
        # position at null island.
        self.assertIsNone(parse_fix(nmea("GPGGA,,,,,,0,,,,,,,,"), UTC))
        self.assertIsNone(parse_fix(nmea("GPRMC,,V,,,,,,,,,,N"), UTC))

    def test_a_corrupted_sentence_is_dropped_not_recorded(self):
        # A wrong checksum means the packet was mangled in flight; trusting it
        # would put a spurious point in the middle of the track.
        good = nmea("GPGGA,140321,4047.2178,N,11912.1804,W,1,09,0.9,1190.0,M,-24.0,M,,")
        self.assertIsNotNone(parse_fix(good, UTC))
        self.assertIsNone(parse_fix(good[:-1] + "0", UTC))

    def test_garbage_never_raises(self):
        # This process has to stay alive for two weeks; one bad datagram must
        # not be able to end the recording.
        for junk in ["", "not nmea", "$", "$GPGGA", "$GPGGA,,,,", "\x00\xff"]:
            self.assertIsNone(parse_fix(junk, UTC))

    def test_southern_and_eastern_hemispheres(self):
        line = nmea("GPGGA,140321,3351.4080,S,15112.9180,E,1,08,0.9,10.0,M,0.0,M,,")
        fix = parse_fix(line, UTC)
        self.assertLess(fix.lat, 0)
        self.assertGreater(fix.lon, 0)

    def test_out_of_range_coordinates_are_rejected(self):
        self.assertIsNone(parse_fix(nmea("GPGGA,140321,9947.2178,N,11912.1804,W,1,09,0.9,1.0,M,0.0,M,,"), UTC))


class TrackWriterTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()

    def test_writes_a_header_once_then_rows(self):
        writer = TrackWriter(self.dir)
        writer.write(Fix(utc=UTC, lat=40.0, lon=-119.0))
        writer.write(Fix(utc=UTC, lat=40.1, lon=-119.1))
        writer.close()

        with open(writer.path_for("2026-08-30")) as handle:
            lines = handle.read().strip().splitlines()
        self.assertEqual(lines[0], ",".join(CSV_HEADER))
        self.assertEqual(len(lines), 3)

    def test_rotates_daily(self):
        writer = TrackWriter(self.dir)
        writer.write(Fix(utc="2026-08-30T23:59:59Z", lat=40.0, lon=-119.0))
        writer.write(Fix(utc="2026-08-31T00:00:01Z", lat=40.0, lon=-119.0))
        writer.close()

        self.assertTrue(os.path.exists(writer.path_for("2026-08-30")))
        self.assertTrue(os.path.exists(writer.path_for("2026-08-31")))

    def test_reopening_appends_rather_than_truncating(self):
        # A reboot mid-burn must not erase the morning.
        first = TrackWriter(self.dir)
        first.write(Fix(utc=UTC, lat=40.0, lon=-119.0))
        first.close()

        second = TrackWriter(self.dir)
        second.write(Fix(utc=UTC, lat=40.1, lon=-119.1))
        second.close()

        with open(second.path_for("2026-08-30")) as handle:
            lines = handle.read().strip().splitlines()
        self.assertEqual(len(lines), 3, "header + both fixes")
        self.assertEqual(lines.count(",".join(CSV_HEADER)), 1)

    def test_every_row_is_on_disk_before_the_next_one(self):
        # The durability claim: a power cut costs at most the row in flight.
        writer = TrackWriter(self.dir)
        writer.write(Fix(utc=UTC, lat=40.0, lon=-119.0))

        with open(writer.path_for("2026-08-30")) as handle:
            self.assertEqual(len(handle.read().strip().splitlines()), 2)
        writer.close()

    def test_missing_optional_fields_leave_empty_columns(self):
        writer = TrackWriter(self.dir)
        writer.write(Fix(utc=UTC, lat=40.0, lon=-119.0))
        writer.close()

        with open(writer.path_for("2026-08-30")) as handle:
            row = handle.read().strip().splitlines()[1].split(",")
        self.assertEqual(len(row), len(CSV_HEADER))
        self.assertEqual(row[3:], ["", "", "", ""])

    def test_coordinates_keep_enough_precision_to_be_useful(self):
        # 7 decimal places is ~1 cm; anything less and the track quantises
        # into a staircase when you zoom in on a lap of the city.
        writer = TrackWriter(self.dir)
        writer.write(Fix(utc=UTC, lat=40.7869634, lon=-119.2030071))
        writer.close()

        with open(writer.path_for("2026-08-30")) as handle:
            row = handle.read().strip().splitlines()[1].split(",")
        self.assertEqual(row[1], "40.7869634")
        self.assertEqual(row[2], "-119.2030071")


if __name__ == "__main__":
    unittest.main()
