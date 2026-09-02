#!/usr/bin/env python3
"""Check that every reference pointer in this tree names a section that exists.

The compiler's comments and `library/` cite the specification as
`reference/<page>.md § <Section>` — the spelling card 0199 replaced the deleted `design/`
chapters with.  A pointer names its section by **title**, so retitling a heading on sysl.sh
breaks it in this repository and nothing here fails: both halves are correct prose, the gate
never reads a comment, and `DocsTests` is in the other repo and never reads this one.

    ./check-pointers.py [path-to-sysl.sh]

Defaults to ../sysl.sh.  Exits non-zero if any pointer resolves to nothing.

Run it in the loop of any card that renames or retitles a reference heading.  Six of the
fourteen dead pointers this found on 2026-08-25 had been written that same morning.
"""

import os
import re
import sys

TREE = os.path.dirname(os.path.abspath(__file__))
SEARCHED = ("shared/src", "library", "doc")

# A pointer's section name may be wrapped across two comment lines, which puts a ` * ` or a
# `// ` in the middle of it.  Collapsing those first is what separates about a hundred false
# failures from the handful of real ones.
CONTINUATION = re.compile(r"\n\s*(?:\*/|\*|//|/\*)[ ]?")
POINTER = re.compile(
    r"`((?:reference|library|getting-started|guides|tour)/[a-z0-9._-]+\.md)(?: § ([^`]+))?`"
)
HEADING = re.compile(r"^#{2,4}\s+(.*?)\s*$")


def headings(site):
    """Every heading on the site, by page path, with backticks stripped."""
    content = os.path.join(site, "docs", "content")
    if not os.path.isdir(content):
        sys.exit(f"{content} is not a directory — pass the path to a sysl.sh checkout")

    found = {}
    for dirpath, _, files in os.walk(content):
        for name in files:
            if not name.endswith(".md"):
                continue
            path = os.path.join(dirpath, name)
            page = os.path.relpath(path, content)
            found[page] = [
                m.group(1).replace("`", "")
                for m in (HEADING.match(line) for line in open(path, encoding="utf-8"))
                if m
            ]
    return found


def main():
    site = sys.argv[1] if len(sys.argv) > 1 else os.path.join(TREE, "..", "sysl.sh")
    pages = headings(site)

    checked = 0
    dead = []
    for sub in SEARCHED:
        for dirpath, _, files in os.walk(os.path.join(TREE, sub)):
            for name in files:
                path = os.path.join(dirpath, name)
                try:
                    text = open(path, encoding="utf-8").read()
                except (OSError, UnicodeDecodeError):
                    continue
                text = CONTINUATION.sub(" ", text)
                for m in POINTER.finditer(text):
                    checked += 1
                    page, section = m.group(1), m.group(2)
                    where = os.path.relpath(path, TREE)
                    if page not in pages:
                        dead.append((where, m.group(0), "no such page"))
                    elif section and not any(
                        # A citation may name a heading's leading words — `§ Platform
                        # selection` for *Platform selection — `__<machines>__`* — so a
                        # prefix counts as a match.
                        h == section or h.startswith(section)
                        for h in pages[page]
                    ):
                        dead.append((where, m.group(0), "no such section"))

    for where, pointer, why in dead:
        print(f"{why}: {where}\n  {pointer}")
    print(f"\n{checked} pointers, {len(dead)} unresolvable")
    return 1 if dead else 0


if __name__ == "__main__":
    sys.exit(main())
