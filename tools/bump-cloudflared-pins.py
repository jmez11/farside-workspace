#!/usr/bin/env python3
"""Update every cloudflared version pin in the repo for a version bump.

Usage:
    python tools/bump-cloudflared-pins.py --old 2026.10.3 --new 2026.10.5 \
        --sha <lowercase sha256 of the new source archive>

Updates (each pattern must match exactly once, or the script fails without
writing anything):
  - tools/build-cloudflared.ps1: default Version and ExpectedSha256
    (uppercase hex, matching the existing convention there)
  - tools/prepare-cloudflared.py: docstring example, docstring default
    line (with today's date), and the default version argument
  - app/src/main/jniLibs/README.md: upstream tree link
  - app/src/androidTest/.../CloudflaredVerification.kt: bundled version check
  - ARCHITECTURE.md: bundled version mention
  - THIRD-PARTY-NOTICES.md: cloudflared version row

Deliberately NOT touched: PROGRESS.md (history), the regenerated
cloudflared-LICENSE / cloudflared-NOTICES.txt / cloudflared-android.patch
(prepare-cloudflared.py owns those), the built .so itself, and
.github/workflows/cloudflared-trybuild.yml (its defaults): the bot token
cannot modify workflow files, so the bump leaves trybuild defaults alone;
pass version/sha explicitly when dispatching a try-build.
"""
import argparse
import datetime
import pathlib
import re
import sys


def main() -> int:
    parser = argparse.ArgumentParser(description="Bump cloudflared version pins.")
    parser.add_argument("--old", required=True, help="currently pinned version")
    parser.add_argument("--new", required=True, help="new upstream version")
    parser.add_argument("--sha", required=True,
                        help="lowercase SHA-256 of the new source archive")
    args = parser.parse_args()

    old, new = args.old, args.new
    sha_lower = args.sha.lower()
    sha_upper = args.sha.upper()
    if not re.fullmatch(r"[0-9a-f]{64}", sha_lower):
        print(f"error: --sha must be a 64-char lowercase hex digest, got {args.sha!r}",
              file=sys.stderr)
        return 2
    today = datetime.datetime.now(datetime.timezone.utc).date().isoformat()

    # (path, old_snippet_or_regex, new_snippet, is_regex)
    edits = [
        ("tools/build-cloudflared.ps1",
         f"[string]$Version = '{old}',",
         f"[string]$Version = '{new}',", False),
        ("tools/build-cloudflared.ps1",
         None,  # SHA replaced below via regex on the existing value
         None, False),
        ("tools/prepare-cloudflared.py",
         f"python tools/prepare-cloudflared.py <source-dir> {old}",
         f"python tools/prepare-cloudflared.py <source-dir> {new}", False),
        ("tools/prepare-cloudflared.py",
         rf"The version defaults to {re.escape(old)} \(current as of \d{{4}}-\d{{2}}-\d{{2}}\)\.",
         f"The version defaults to {new} (current as of {today}).", True),
        ("tools/prepare-cloudflared.py",
         f'else "{old}"',
         f'else "{new}"', False),
        ("app/src/main/jniLibs/README.md",
         f"tree/{old}",
         f"tree/{new}", False),
        ("app/src/androidTest/java/com/farside/workspace/CloudflaredVerification.kt",
         f'"{old}-farside"',
         f'"{new}-farside"', False),
        ("ARCHITECTURE.md",
         f"{old}-farside",
         f"{new}-farside", False),
        ("THIRD-PARTY-NOTICES.md",
         f"| cloudflared (bundled arm64 binary) | {old} |",
         f"| cloudflared (bundled arm64 binary) | {new} |", False),
    ]

    # Load every touched file first so a failure leaves the tree untouched.
    texts: dict[str, str] = {}
    for path, _, _, _ in edits:
        if path not in texts:
            texts[path] = pathlib.Path(path).read_text(encoding="utf-8")

    def replace_once(path: str, old_snippet: str, new_snippet: str) -> None:
        text = texts[path]
        count = text.count(old_snippet)
        if count != 1:
            print(f"error: expected exactly 1 occurrence of {old_snippet!r} "
                  f"in {path}, found {count}", file=sys.stderr)
            raise SystemExit(1)
        texts[path] = text.replace(old_snippet, new_snippet)

    def replace_regex_once(path: str, pattern: str, new_snippet: str) -> None:
        text = texts[path]
        new_text, count = re.subn(pattern, new_snippet, text)
        if count != 1:
            print(f"error: expected exactly 1 match of /{pattern}/ in {path}, "
                  f"found {count}", file=sys.stderr)
            raise SystemExit(1)
        texts[path] = new_text

    for path, old_snippet, new_snippet, is_regex in edits:
        if old_snippet is None:
            continue  # SHA edits handled below
        if is_regex:
            replace_regex_once(path, old_snippet, new_snippet)
        else:
            replace_once(path, old_snippet, new_snippet)

    # SHA pins: match the existing value (unknown to this script) and replace
    # it, preserving each file's hex case convention.
    replace_regex_once(
        "tools/build-cloudflared.ps1",
        r"\[string\]\$ExpectedSha256 = '[0-9A-Fa-f]{64}',",
        f"[string]$ExpectedSha256 = '{sha_upper}',")

    for path, text in texts.items():
        pathlib.Path(path).write_text(text, encoding="utf-8")
    print(f"bumped cloudflared pins {old} -> {new} "
          f"(sha {sha_lower[:12]}...) in {len(texts)} files")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
