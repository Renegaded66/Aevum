#!/usr/bin/env python3
"""
M34 — Audit: feste Texte in Kotlin-Quellen.

Findet String-Literale, die wie MENSCHENLESBARER Text aussehen (Leerzeichen
oder Großbuchstaben mitten im Satz, mindestens drei Buchstaben) und nicht
offensichtlich Schlüssel, Route, Query, Log-Tag oder Test-ID sind.

Aufruf:
    python3 docs/tools/audit_hardcoded_strings.py
    python3 docs/tools/audit_hardcoded_strings.py --all   (auch Verdachtsfälle)
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2] / "app" / "src" / "main" / "java"

# Zeilen, in denen ein Literal NIE UI-Text ist.
SKIP_LINE_MARKERS = (
    "Log.",
    "@Query(",
    "@DatabaseView(",
    "testTag",
    "TAG =",
    "private const val",
    "const val",
    "EXTRA_",
    "putExtra(",
    "getStringExtra(",
    "contentType",
    "MediaType",
    "application/json",
    "text/plain",
    "utf-8",
    "mimeType",
    "DateTimeFormatter.ofPattern",
    "SimpleDateFormat(",
    "String.format(",
    "java.util.Locale",
    "Locale.forLanguageTag",
    "package:",
    "https://",
    "http://",
    "android.intent",
    "android.permission",
    "PREF",
    "KEY_",
    "sourceType =",
    "status =",
    "route =",
    "id = \"",
    "categoryId = \"",
    "typeId = \"",
    "sourceId = \"",
    "kind = \"",
    "action = \"",
)

# Typische Nicht-Text-Muster im Literal selbst.
NON_TEXT = re.compile(
    r"^("
    r"[a-z0-9_.:/?#&=%-]+"          # Schlüssel, Routen, IDs, URLs
    r"|[A-Z][A-Za-z0-9_]*"          # einzelnes Wort in PascalCase (Enum-Name)
    r"|[^A-Za-z]*"                  # nur Symbole/Emoji
    r")$"
)

# Wörter, die auf Code statt Prosa hindeuten.
CODE_WORDS = re.compile(
    r"(Json|json|Uri|uri|Intent|intent|Extra|extra|Prefs|prefs|_id|Id\b|"
    r"yyyy|MM|dd|HH|mm|ss|SSS|\\u[0-9A-Fa-f]{4}|%[0-9]*\$?[sd])"
)

LITERAL = re.compile(r'"((?:[^"\\]|\\.)*)"')


def strip_comments(text: str) -> str:
    """Block- und Zeilenkommentare entfernen (String-Inhalte bleiben)."""
    out: list[str] = []
    i = 0
    n = len(text)
    in_line = False
    in_block = 0
    in_string = False
    while i < n:
        char = text[i]
        two = text[i : i + 2]
        if in_line:
            if char == "\n":
                in_line = False
                out.append(char)
            else:
                out.append(" ")
            i += 1
            continue
        if in_block:
            if two == "*/":
                in_block -= 1
                out.append("  ")
                i += 2
                continue
            out.append("\n" if char == "\n" else " ")
            i += 1
            continue
        if in_string:
            out.append(char)
            if char == "\\":
                if i + 1 < n:
                    out.append(text[i + 1])
                i += 2
                continue
            if char == '"':
                in_string = False
            i += 1
            continue
        if two == "//":
            in_line = True
            out.append("  ")
            i += 2
            continue
        if two == "/*":
            in_block += 1
            out.append("  ")
            i += 2
            continue
        if char == '"':
            in_string = True
        out.append(char)
        i += 1
    return "".join(out)


def looks_like_text(value: str) -> bool:
    stripped = value.strip()
    if len(stripped) < 4:
        return False
    if NON_TEXT.match(stripped):
        return False
    if CODE_WORDS.search(stripped):
        return False
    letters = sum(1 for c in stripped if c.isalpha())
    if letters < 3:
        return False
    # Mindestens ein Leerzeichen ODER ein großer Anfangsbuchstabe + Kleinbuchstaben.
    if " " in stripped:
        return True
    return bool(re.search(r"[A-ZÄÖÜ][a-zäöüß]{3,}", stripped))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--all", action="store_true", help="auch Ein-Wort-Treffer zeigen")
    args = parser.parse_args()

    findings: list[tuple[str, int, str]] = []
    for path in sorted(ROOT.rglob("*.kt")):
        raw = path.read_text(encoding="utf-8", errors="replace")
        code = strip_comments(raw)
        for number, line in enumerate(code.split("\n"), start=1):
            if any(marker in line for marker in SKIP_LINE_MARKERS):
                continue
            for match in LITERAL.finditer(line):
                value = match.group(1)
                if not looks_like_text(value):
                    continue
                if not args.all and " " not in value:
                    continue
                findings.append((str(path.relative_to(ROOT)), number, value))

    for file, line, value in findings:
        print(f"{file}:{line}: {value}")
    print(f"\n{len(findings)} Verdachtsfälle", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
