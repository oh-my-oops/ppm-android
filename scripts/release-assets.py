#!/usr/bin/env python3
"""Names a release build's files as the app's GitHub release carries them, and prints their SHA-256.

    ./gradlew :app:assembleRelease -PppmVersion=0.1.1
    python3 -I scripts/release-assets.py <out dir>

writes ppm-android-<version>.apk and, for each Baseline Profile format the build wrote, the profile
as ppm-android-<version>.api<first Android API that reads it>.dm. Upload all of them to the release
tagged android-v<version>: the app downloads the profile for its device beside the APK and installs
both at once, so Android compiles the update as it installs it (AppRelease.profileFor).
"""
import hashlib
import json
import os
import shutil
import sys

BUILD = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "build", "outputs", "apk", "release")


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    out = sys.argv[1]
    meta = json.load(open(os.path.join(BUILD, "output-metadata.json"), encoding="utf-8"))
    (element,) = meta["elements"]
    version = element["versionName"]
    files = [(os.path.join(BUILD, element["outputFile"]), f"ppm-android-{version}.apk")]
    for profiles in meta.get("baselineProfiles", []):
        (dm,) = profiles["baselineProfiles"]
        files.append((os.path.join(BUILD, dm), f"ppm-android-{version}.api{profiles['minApi']}.dm"))
    if len(files) == 1:
        sys.exit("The build wrote no Baseline Profile: is app/src/release/generated/baselineProfiles there?")
    os.makedirs(out, exist_ok=True)
    for source, name in files:
        target = os.path.join(out, name)
        shutil.copyfile(source, target)
        print(hashlib.sha256(open(target, "rb").read()).hexdigest(), name)


main()
