#!/usr/bin/env python3
"""Guard: locale keys must not rename class/id/name identifiers.

Copyright (C) 2026 Scoutro contributors
Scoutro is an independent community project based on YaCy.
Licensed under the GNU General Public License, version 2 or (at your option)
any later version.

YaCy translates by plain word replacement in the raw HTML (see
locales/README-locales.md). A key that equals a class, id or name token of the
page renames that token in the translated copy: e.g. "search==suchen" turned
<form class="search small"> into class="suchen small" and <input id="search">
into id="suchen", so the Scoutro styles and the autocomplete no longer matched.

Usage:  python3 test/scoutro-ui/check-locale-identifiers.py [page.html ...]
Without arguments the Scoutro search pages and LLM selection are checked
(index.html, yacysearch.html, LLMSelection_p.html). Exit status 1 lists every collision.
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PAGES = sys.argv[1:] or ["index.html", "yacysearch.html", "LLMSelection_p.html"]
ATTR = re.compile(r'\b(?:class|id|name|for)\s*=\s*"([^"#]*)"')


def identifiers(page):
    tokens = set()
    for value in ATTR.findall((ROOT / "htroot" / page).read_text(encoding="utf-8", errors="replace")):
        tokens.update(value.split())
    return tokens


def sections(lng):
    current = None
    for line in lng.read_text(encoding="utf-8", errors="replace").splitlines():
        if line.startswith("#File: "):
            current = line[7:].strip()
        elif current and "==" in line and not line.startswith("#"):
            yield current, line.split("==", 1)[0]


def main():
    ids = {p: identifiers(p) for p in PAGES}
    problems = []
    for lng in sorted((ROOT / "locales").glob("*.lng")):
        for page, key in sections(lng):
            if page in ids and key in ids[page]:
                problems.append("%s [%s]: key %r renames a class/id/name token" % (lng.name, page, key))
    for p in problems:
        print("FAIL " + p)
    print("checked %d page(s) against %d locale file(s): %d collision(s)"
          % (len(PAGES), len(list((ROOT / "locales").glob("*.lng"))), len(problems)))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
