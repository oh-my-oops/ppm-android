#!/usr/bin/env python3
"""
Writes a large project folder for checking the explorer, the file palette and the @-picker against a project the size of a real
monorepo (the web's nxsys-workspace has 181k entries, most of them in node_modules): 200,003 files in 10,842 folders by default.

    python3 android/scripts/gen-big-project.py /path/to/new-empty-dir [--files 200000] [--ignored 0.8]

What it writes, and only inside the target directory (which has to be new or empty; nothing is ever deleted or overwritten):
  - .gitignore            node_modules/, dist/, *.log
  - README.md, package.json   the only files with text in them, to open in the editor
  - apps/appNN/modules/modNN/src/NN/fileNN.kt   the tracked part: empty files, 20 to a folder, 6 levels deep
  - node_modules/pkgNNNN/...                    the ignored part: empty files, 40 to a package, under a .gitignore'd folder

It imports nothing from the PPM sources and starts no server. Add the folder to PPM as a project afterwards and open it on the
device; then, as the tester's checklist asks, time the first expand of the root, the first open of the file palette (Double Shift)
and a search for "file19", and watch that scrolling the tree stays smooth and nothing freezes for seconds.
"""
import argparse
import os
import sys
import time

TRACKED_PER_DIR = 20
IGNORED_PER_PACKAGE = 40
SRC_DIRS_PER_MODULE = 5
MODULES_PER_APP = 20


def touch(path: str) -> None:
    os.close(os.open(path, os.O_CREAT | os.O_WRONLY, 0o644))


def write_text(path: str, text: str) -> None:
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(text)


def refuse_unsafe_target(target: str) -> None:
    home = os.path.realpath(os.path.expanduser("~"))
    if target in ("/", home) or os.path.dirname(target) == target:
        sys.exit("Refusing to write into " + target)
    if os.path.exists(target) and (not os.path.isdir(target) or os.listdir(target)):
        sys.exit(target + " exists and is not an empty directory; give a new or empty one.")


def main() -> None:
    parser = argparse.ArgumentParser(description="Writes a big project folder for the explorer checks.")
    parser.add_argument("target", help="a new or empty directory; the only place anything is written")
    parser.add_argument("--files", type=int, default=200_000, help="files to write besides the three text ones (default 200000); folders come on top")
    parser.add_argument("--ignored", type=float, default=0.8, help="share of them under node_modules (default 0.8)")
    args = parser.parse_args()
    if args.files < 1 or not 0.0 <= args.ignored < 1.0:
        sys.exit("--files has to be at least 1 and --ignored in [0, 1).")

    target = os.path.realpath(args.target)
    refuse_unsafe_target(target)
    os.makedirs(target, exist_ok=True)
    started = time.time()

    tracked_wanted = max(1, round(args.files * (1.0 - args.ignored)))
    ignored_wanted = args.files - tracked_wanted
    write_text(os.path.join(target, ".gitignore"), "node_modules/\ndist/\n*.log\n")
    write_text(os.path.join(target, "README.md"), "# Big project\n\nWritten by android/scripts/gen-big-project.py. Open me.\n")
    write_text(os.path.join(target, "package.json"), '{\n  "name": "big-project",\n  "private": true\n}\n')

    tracked = 0
    leaf = 0
    while tracked < tracked_wanted:
        app, rest = divmod(leaf, MODULES_PER_APP * SRC_DIRS_PER_MODULE)
        module, source = divmod(rest, SRC_DIRS_PER_MODULE)
        folder = os.path.join(target, "apps", "app%02d" % app, "modules", "mod%02d" % module, "src", "%02d" % source)
        os.makedirs(folder, exist_ok=True)
        for number in range(min(TRACKED_PER_DIR, tracked_wanted - tracked)):
            touch(os.path.join(folder, "file%02d.kt" % number))
            tracked += 1
        leaf += 1

    ignored = 0
    package = 0
    while ignored < ignored_wanted:
        folder = os.path.join(target, "node_modules", "pkg%04d" % package, "lib")
        os.makedirs(folder, exist_ok=True)
        for number in range(min(IGNORED_PER_PACKAGE, ignored_wanted - ignored)):
            touch(os.path.join(folder, "file%02d.js" % number))
            ignored += 1
        package += 1

    folders = sum(len(names) for _, names, _ in os.walk(target))
    print("wrote %d files (%d tracked, %d under node_modules) in %d folders to %s in %.1f s"
          % (tracked + ignored + 3, tracked + 3, ignored, folders, target, time.time() - started))


if __name__ == "__main__":
    main()
