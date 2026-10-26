#!/usr/bin/env python3
"""Folds Baseline Profiles generated on other devices into app/src/release/generated/baselineProfiles.

`:app:generateBaselineProfile` replaces that directory with what the devices of one run found,
and a phone never reaches the desktop layout's code, nor a tablet the mobile one's. With one
emulator up at a time, generate on the phone, keep its directory, generate on the tablet, then:

    python3 -I scripts/merge-baseline-profiles.py <the phone run's directory>

Every rule of either is kept, and one both found keeps the flags of both (H hot, S startup,
P post-startup). Rules are written in the plugin's order, each class before its methods, so a
merged file differs from a generated one only by the rules it adds.
"""
import os
import sys

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "release", "generated", "baselineProfiles")
FILES = ("baseline-prof.txt", "startup-prof.txt")


def read(path, rules):
    for line in open(path, encoding="utf-8"):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        body = line.lstrip("HSP")
        rules[body] = rules.get(body, "") + line[: len(line) - len(body)]


def order(body):
    owner, arrow, method = body.partition("->")
    return (owner.rstrip(";"), 1 if arrow else 0, method)


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    for name in FILES:
        rules = {}
        for directory in [OUT, *sys.argv[1:]]:
            path = os.path.join(directory, name)
            if os.path.exists(path):
                read(path, rules)
        with open(os.path.join(OUT, name), "w", encoding="utf-8") as out:
            for body in sorted(rules, key=order):
                out.write("".join(flag for flag in "HSP" if flag in rules[body]) + body + "\n")
        print(f"{name}: {len(rules)} rules")


main()
