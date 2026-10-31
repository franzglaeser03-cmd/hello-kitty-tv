"""dnsfilter.sh against a pretend Fire TV: which DNS servers it puts inside the tunnel.

Run: python3 -m unittest discover -s tests -v
"""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
GATEWAY = "198.51.100.1"

# Only the lines dnsfilter.sh patches and checks; the real template has many more.
TEMPLATE = """filterAutoUpdateURL = https://example.org/list.txt
previousAutoUpdateURL = <none>
detectDNS = true
fallbackDNS = 203.0.113.9::53::UDP
routeIPs =
routeUnderlyingDNS = false
showInitialInfoPopUp = true
reloadIntervalDays = 7
filterHostsFile = FILTERHOSTS.TXT
androidAppWhiteList = com.android.vending
AUTOSTART = true
"""

# Stands in for curl: answers each download with the file named like the URL's last part.
CURL = """#!/usr/bin/env bash
while [[ $# -gt 1 ]]; do [[ $1 == -o ]] && { out=$2; shift; }; shift; done
cp "$FAKE_CURL_DIR/${1##*/}" "$out"
"""


class GatewayTest(unittest.TestCase):
    """Android keeps a default route per network; only one of them reaches the internet."""
    DEAD = "default via 203.0.113.254 dev eth0 table 1002 proto static\n" \
           f"default via {GATEWAY} dev wlan0 table 1007 proto static"
    AGENTS = "NetworkAgentInfo{ ni{[type: Ethernet[]]} lp{{ServerAddress: 203.0.113.254 }} nc{[ Capabilities: INTERNET ]} }\n" \
             f"NetworkAgentInfo{{ ni{{[type: WIFI[]]}} lp{{{{ServerAddress: {GATEWAY} }}}} nc{{[ Capabilities: INTERNET&VALIDATED ]}} }}"

    def gateway(self, **state):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            path = tmp / "state.json"
            path.write_text(json.dumps(dict(state, packages={})))
            log = tmp / "commands.log"
            log.touch()
            env = dict(os.environ, ADB=str(ROOT / "tests" / "fake_tv.py"), SERIAL="test-tv",
                       FAKE_TV_STATE=str(path), FAKE_TV_LOG=str(log))
            result = subprocess.run(
                ["bash", "-c", 'source "$1"; gateway', "bash", str(ROOT / "scripts" / "lib.sh")],
                env=env, capture_output=True, text=True, timeout=30)
        return result.stdout.strip()

    def test_the_route_in_use_beats_a_dead_wired_gateway_listed_first(self):
        self.assertEqual(self.gateway(
            routes=self.DEAD,
            route_get=f"1.1.1.1 via {GATEWAY} dev wlan0 table 1007 src 198.51.100.7 uid 2000"), GATEWAY)

    def test_behind_a_vpn_the_validated_network_decides(self):
        self.assertEqual(self.gateway(
            routes=self.DEAD, route_get="1.1.1.1 dev tun0 table 1050 src 203.0.113.7 uid 2000",
            connectivity=self.AGENTS), GATEWAY)

    def test_a_tv_answering_neither_still_reads_the_route_table(self):
        self.assertEqual(self.gateway(routes=f"default via {GATEWAY} dev wlan0 table 1003 proto static"),
                         GATEWAY)


class RouteTest(unittest.TestCase):
    def route_ips(self, props):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            files = tmp / "downloads"
            files.mkdir()
            (files / "dnsfilter.conf").write_text(TEMPLATE)
            (files / "native.amazon-onlydomains.txt").write_text("# list\ndevice-metrics-us.amazon.com\n")
            bin_dir = tmp / "bin"
            bin_dir.mkdir()
            (bin_dir / "curl").write_text(CURL)
            (bin_dir / "curl").chmod(0o755)
            state = tmp / "state.json"
            state.write_text(json.dumps({
                "packages": {"dnsfilter.android": {"system": False, "enabled": True}},
                "versions": {"dnsfilter.android": 1506103},
                "routes": f"default via {GATEWAY} dev wlan0 table 1003 proto static",
                "props": props,
                "pushed": str(tmp / "pushed"),
            }))
            log = tmp / "commands.log"
            log.touch()
            env = dict(os.environ, ADB=str(ROOT / "tests" / "fake_tv.py"), ADB_SERIAL="test-tv",
                       FAKE_TV_STATE=str(state), FAKE_TV_LOG=str(log), FAKE_CURL_DIR=str(files),
                       CONF=str(tmp / "dumbtv.conf"), REPORT=str(tmp / "report"),
                       PATH=f"{bin_dir}{os.pathsep}{os.environ['PATH']}")
            for name in ("DNS_UPSTREAM", "SYSTEM_DNS", "FILTER_AUTOSTART", "DNSFILTER"):
                env.pop(name, None)
            result = subprocess.run(
                ["bash", str(ROOT / "scripts" / "dnsfilter.sh"), "-y", "--no-reboot"],
                env=env, capture_output=True, text=True, timeout=120,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            conf = (tmp / "pushed" / "dnsfilter.conf").read_text().splitlines()
        return [line for line in conf if line.startswith("routeIPs")]

    def test_ipv6_and_extra_dns_servers_go_inside_the_tunnel(self):
        # A router that hands out an IPv6 resolver by router advertisement, plus a public
        # resolver in net.dns2: lookups to either would otherwise skip the filter.
        routes = self.route_ips({
            "net.dns1": "2001:db8::1",
            "net.dns2": "203.0.113.53",
            "net.dns3": GATEWAY,
            "net.dns4": "fd00::53",
        })
        self.assertEqual(routes, [f"routeIPs = {GATEWAY}; 2001:db8::1; 203.0.113.53; fd00::53"])

    def test_gateway_alone_when_the_tv_knows_no_other_server(self):
        routes = self.route_ips({"net.dns1": GATEWAY, "net.dns2": "127.0.0.1", "net.dns3": "::1",
                                 "net.dns4": "not-an-address"})
        self.assertEqual(routes, [f"routeIPs = {GATEWAY}"])


if __name__ == "__main__":
    unittest.main()
