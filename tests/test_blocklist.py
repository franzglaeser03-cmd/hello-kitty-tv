"""The DNS filter's list follows the owner's answers about Prime Video and Alexa.

Run: python3 -m unittest discover -s tests -v
"""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
FEED = "# feed\napi.us-east-1.aiv-delivery.net\nmetrics.example-tracker.com\n" \
       "s3-dub-ww.cf.videorolls.row.aiv-cdn.net\n"


def lib(script, **env):
    with tempfile.TemporaryDirectory() as tmp:
        feed = Path(tmp) / "feed.txt"
        feed.write_text(FEED)
        result = subprocess.run(
            ["bash", "-c", f'source "$1"; {script} "$2"', "bash",
             str(ROOT / "scripts" / "lib.sh"), str(feed)],
            env=dict(os.environ, **env), capture_output=True, text=True,
        )
    return result.stdout.split()


class BlocklistTest(unittest.TestCase):
    def test_defaults_keep_prime_and_alexa_working(self):
        hosts = lib("compose_blocklist", PRIME="1", ALEXA="1")
        self.assertIn("metrics.example-tracker.com", hosts)
        self.assertIn("softwareupdates.amazon.com", hosts)
        self.assertNotIn("api.us-east-1.aiv-delivery.net", hosts)
        self.assertNotIn("device-messaging-na.amazon.com", hosts)
        self.assertNotIn("api.amazonalexa.com", hosts)
        # Prime's promo clips stay blocked even though the host is on its video CDN.
        self.assertIn("s3-dub-ww.cf.videorolls.row.aiv-cdn.net", hosts)

    def test_no_prime_no_alexa_blocks_both(self):
        hosts = lib("compose_blocklist", PRIME="0", ALEXA="0")
        self.assertIn("api.us-east-1.aiv-delivery.net", hosts)
        self.assertIn("device-messaging-na.amazon.com", hosts)
        self.assertIn("api.amazonalexa.com", hosts)

    def test_a_general_list_blocks_ads_but_not_what_the_apps_need(self):
        with tempfile.TemporaryDirectory() as tmp:
            feed = Path(tmp) / "feed.txt"
            feed.write_text(FEED)
            ads = Path(tmp) / "ads.txt"
            ads.write_text("# ads\nads.example.com\nnetflix.com\ncustomerevents.netflix.com\n"
                           "YTIMG.com\namazonalexa.com\nmetrics.example-tracker.com\n")
            result = subprocess.run(
                ["bash", "-c", 'source "$1"; compose_blocklist "$2" "$3"', "bash",
                 str(ROOT / "scripts" / "lib.sh"), str(feed), str(ads)],
                env=dict(os.environ, PRIME="1", ALEXA="1"), capture_output=True, text=True)
        hosts = result.stdout.split()
        self.assertIn("ads.example.com", hosts)
        self.assertIn("app-measurement.com", hosts)
        # An allowed host's parent would take the host with it; a listed subdomain stays.
        self.assertNotIn("netflix.com", hosts)
        self.assertNotIn("ytimg.com", hosts)
        self.assertIn("customerevents.netflix.com", hosts)
        # With Alexa kept, nothing it needs comes back through the general list.
        self.assertNotIn("amazonalexa.com", hosts)
        self.assertEqual(hosts.count("metrics.example-tracker.com"), 1)

    def test_verify_expects_only_what_was_blocked(self):
        default = lib("expected_blocked", PRIME="1", ALEXA="1")
        self.assertNotIn("api.amazonalexa.com", default)
        self.assertNotIn("device-messaging-na.amazon.com", default)
        strict = lib("expected_blocked", PRIME="0", ALEXA="0")
        self.assertIn("api.amazonalexa.com", strict)
        self.assertIn("device-messaging-na.amazon.com", strict)


if __name__ == "__main__":
    unittest.main()
