#!/usr/bin/env python3
"""
M34 — Prüft eine übersetzte Ressourcen-Sprache gegen die englische Quelle.

Aufruf:
    python3 docs/tools/validate_language.py --lang fr
    python3 docs/tools/validate_language.py --lang fr --fix-apostrophes

Geprüft wird:
  1. XML ist wohlgeformt (fängt rohe `&`, `<`, `>`).
  2. Jede Datei hat exakt dieselben String-Keys wie `values/`.
  3. Platzhalter (`%1$s`, `%2$d`, `%%`) stimmen mit dem Original überein.
  4. Kein Wert ist leer.
  5. Apostrophe sind Android-konform escaped (`\\'`) — ein rohes `'` bricht
     den Build (AAPT: „Apostrophe not preceded by \\").
  6. Kein Wert ist zeichengleich mit dem Englischen (außer Endonymen).

Exit-Code 0 = sauber, 1 = Fehler (Ausgabe nennt Datei/Key).
"""

from __future__ import annotations

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

RES = Path(__file__).resolve().parents[2] / "app" / "src" / "main" / "res"
# NUR echte Format-Platzhalter. Wichtig: kein Leerzeichen im Muster —
# sonst wird „40% Nachtanteil" als Platzhalter „% N" gelesen.
PLACEHOLDER = re.compile(r"%%|%(?:\d+\$)?[-#+0.,]*\d*(?:\.\d+)?[a-zA-Z]")

DONT_CHECK_IDENTICAL = {"app_name", "app_name_debug"}


def read(path: Path) -> dict[str, str]:
    root = ET.parse(path).getroot()
    out: dict[str, str] = {}
    for element in root:
        if element.tag == "string" and element.attrib.get("name"):
            out[element.attrib["name"]] = element.text or ""
    return out


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--lang", required=True)
    parser.add_argument(
        "--fix-apostrophes",
        action="store_true",
        help="rohe Apostrophe automatisch escapen (statt nur melden)",
    )
    args = parser.parse_args()

    target_dir = RES / f"values-{args.lang}"
    if not target_dir.is_dir():
        print(f"FEHLT: {target_dir}")
        return 1

    problems: list[str] = []
    files = sorted((RES / "values").glob("strings*.xml"))

    for source_file in files:
        target_file = target_dir / source_file.name
        if not target_file.exists():
            problems.append(f"{source_file.name}: Datei fehlt")
            continue

        # ── 1. XML wohlgeformt? ──────────────────────────────────────────
        try:
            target = read(target_file)
        except ET.ParseError as error:
            problems.append(f"{source_file.name}: XML kaputt — {error}")
            continue
        source = read(source_file)

        # ── 2. Keys ──────────────────────────────────────────────────────
        missing = source.keys() - target.keys()
        extra = target.keys() - source.keys()
        if missing:
            problems.append(f"{source_file.name}: {len(missing)} Keys fehlen ({sorted(missing)[:5]})")
        if extra:
            problems.append(f"{source_file.name}: {len(extra)} unbekannte Keys ({sorted(extra)[:5]})")

        # ── 3./4./6. Inhalt ──────────────────────────────────────────────
        for key, source_value in source.items():
            value = target.get(key)
            if value is None:
                continue
            if not value.strip():
                problems.append(f"{source_file.name}:{key}: leer")
                continue
            expected = sorted(PLACEHOLDER.findall(source_value))
            actual = sorted(PLACEHOLDER.findall(value))
            if expected != actual:
                problems.append(
                    f"{source_file.name}:{key}: Platzhalter {actual} statt {expected}"
                )
            # Einzelne Wörter sind oft Lehnwörter („Timeline", „Sport",
            # „Export") — geprüft wird nur echter Fließtext (≥ 4 Wörter).
            words = len(value.split())
            if (
                key not in DONT_CHECK_IDENTICAL
                and not key.startswith("language_")
                and args.lang != "de"
                and words >= 4
                and source_value.strip() == value.strip()
            ):
                problems.append(f"{source_file.name}:{key}: unübersetzt (\"{value[:60]}\")")

        # ── 5. Apostrophe ────────────────────────────────────────────────
        raw = target_file.read_text(encoding="utf-8")
        if args.fix_apostrophes:
            fixed = re.sub(r"(?<!\\)'", r"\\'", raw)
            # Nur zurückschreiben, wenn es tatsächlich etwas geändert hat.
            if fixed != raw:
                target_file.write_text(fixed, encoding="utf-8")
                raw = fixed
        for match in re.finditer(r"<string[^>]*>(.*?)</string>", raw, re.S):
            body = match.group(1)
            if re.search(r"(?<!\\)'", body):
                problems.append(f"{source_file.name}: roher Apostroph in \"{body[:50]}\"")

    if problems:
        print(f"{args.lang}: {len(problems)} Probleme")
        for problem in problems[:40]:
            print("  -", problem)
        if len(problems) > 40:
            print(f"  … und {len(problems) - 40} weitere")
        return 1
    print(f"{args.lang}: OK ({len(files)} Dateien)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
