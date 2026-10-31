#!/usr/bin/env python3
"""Downloads each streaming app's current icon from Apple's public App Store listing, named after
the TV app's package, for tools/glass-icons.py. Some already carry iOS 26's glass rim.

Run: python3 tools/fetch-icons.py OUT_DIR
"""
import json
from pathlib import Path
import sys
import urllib.parse
import urllib.request

# TV package, then the iOS app's bundle id or a search term.
APPS = {
    "com.netflix.ninja": ("bundle", "com.netflix.Netflix"),
    "com.amazon.firebat": ("bundle", "com.amazon.aiv.AIVApp"),
    "org.smarttube.stable": ("bundle", "com.google.ios.youtube"),
    "com.spotify.tv.android": ("bundle", "com.spotify.client"),
    "com.disney.disneyplus": ("bundle", "com.disney.disneyplus"),
    "com.hbo.hbonow": ("term", "HBO Max"),
    "com.apple.atve.amazon.appletv": ("term", "Apple TV"),
    "com.zdf.android.mediathek": ("term", "ZDFmediathek"),
    "de.swr.ard.avp.mobile.android.amazon": ("term", "ARD Mediathek"),
    "tv.arte.plus7": ("term", "arte"),
}


def lookup(kind, query):
    base = "https://itunes.apple.com/"
    quoted = urllib.parse.quote(query)
    url = (f"{base}lookup?country=de&bundleId={quoted}" if kind == "bundle"
           else f"{base}search?country=de&entity=software&limit=5&term={quoted}")
    results = json.load(urllib.request.urlopen(url, timeout=25)).get("results", [])
    if kind == "term":
        first_word = query.lower().split()[0]
        for result in results:
            if first_word in result.get("trackName", "").lower():
                return result
    return results[0] if results else None


def main():
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    for package, (kind, query) in APPS.items():
        result = lookup(kind, query)
        if result is None:
            print(package, "not found")
            continue
        art = result.get("artworkUrl512") or result["artworkUrl100"]
        art = art.replace("512x512bb", "1024x1024bb").replace("100x100bb", "1024x1024bb")
        urllib.request.urlretrieve(art, out / f"{package}.jpg")
        print(package, result.get("trackName", ""))


if __name__ == "__main__":
    main()
