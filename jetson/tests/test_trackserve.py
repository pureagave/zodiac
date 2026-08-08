import os
import tempfile
import unittest

from zvision.trackserve import listing, safe_name


class SafeNameTest(unittest.TestCase):
    def test_accepts_a_track_file(self):
        self.assertEqual(safe_name("/zodiac-track-2026-08-30.csv"), "zodiac-track-2026-08-30.csv")

    def test_refuses_directory_traversal(self):
        # The one way a read-only file server becomes a way to read /etc/shadow.
        for attack in [
            "/../../../../etc/passwd",
            "/..%2f..%2fetc%2fpasswd",
            "/%2e%2e/%2e%2e/etc/passwd",
            "/subdir/zodiac-track.csv",
            "//etc/passwd",
            "/....//etc/passwd",
        ]:
            self.assertIsNone(safe_name(attack), f"should refuse {attack!r}")

    def test_refuses_non_track_files(self):
        # Only our CSVs are ours to hand out.
        for other in ["/id_rsa", "/config.yaml", "/zodiac-track.csv.gz", "/.env"]:
            self.assertIsNone(safe_name(other), f"should refuse {other!r}")

    def test_refuses_empty_and_bare_dots(self):
        for odd in ["/", "", "/.", "/.."]:
            self.assertIsNone(safe_name(odd))

    def test_ignores_a_query_string_rather_than_being_confused_by_it(self):
        self.assertEqual(safe_name("/zodiac-track-2026-08-30.csv?x=1"), "zodiac-track-2026-08-30.csv")


class ListingTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()

    def _touch(self, name, body=b"x"):
        with open(os.path.join(self.dir, name), "wb") as handle:
            handle.write(body)

    def test_lists_track_files_newest_first_with_sizes(self):
        # Newest first so a mirror can stop as soon as it recognises a file it
        # already has, instead of walking the whole burn every poll.
        self._touch("zodiac-track-2026-08-29.csv", b"a")
        self._touch("zodiac-track-2026-08-30.csv", b"bb")

        result = listing(self.dir)

        self.assertEqual([r["name"] for r in result],
                         ["zodiac-track-2026-08-30.csv", "zodiac-track-2026-08-29.csv"])
        self.assertEqual(result[0]["bytes"], 2)

    def test_ignores_anything_that_is_not_a_track_file(self):
        self._touch("zodiac-track-2026-08-30.csv")
        self._touch("notes.txt")
        self._touch("secret.pem")

        self.assertEqual([r["name"] for r in listing(self.dir)], ["zodiac-track-2026-08-30.csv"])

    def test_a_missing_directory_is_empty_not_an_error(self):
        # The mirror must degrade to "nothing yet", never to a 500 loop.
        self.assertEqual(listing(os.path.join(self.dir, "nope")), [])


if __name__ == "__main__":
    unittest.main()
