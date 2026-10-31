#!/usr/bin/env python3
"""
M34 — Ressourcen-Übersetzer für Aevum.

Übersetzt alle `app/src/main/res/values/*.xml` (englische Quelle) in die
Zielsprachen und schreibt `values-<code>/<datei>.xml`.

Warum ein Skript und keine Handarbeit:
  • ~1.700 Strings × 9 Sprachen. Von Hand wäre das weder vollständig noch
    konsistent — und jede spätere Textänderung müsste neunmal nachgezogen
    werden.
  • Platzhalter (`%1$s`, `\n`) und Android-Escapes (`\'`, `&amp;`) sind die
    eigentliche Fehlerquelle. Sie werden hier tokenisiert und unverändert
    zurückgesetzt; danach wird JEDER String validiert.

Aufruf:
    python3 docs/tools/translate_resources.py --langs es fr pt
    python3 docs/tools/translate_resources.py --all

Das Skript ist idempotent: bereits vorhandene Zieldateien werden komplett
neu erzeugt (keine Reste alter Keys).
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

RES = Path(__file__).resolve().parents[2] / "app" / "src" / "main" / "res"
SOURCE_DIR = RES / "values"

# Endonyme und Eigennamen: werden NICHT übersetzt (in allen Sprachen gleich).
DO_NOT_TRANSLATE = {"app_name", "app_name_debug"}
DO_NOT_TRANSLATE_PREFIX = ("language_",)

# WICHTIG (gemessen, nicht geraten): Der öffentliche Google-Endpunkt
# beantwortet POST mit 429, GET mit 200 — auch bei gleicher IP und gleichem
# Text. Deshalb wird ausschließlich GET benutzt. Der `at`-Client
# (translate.google.com) hat zusätzlich ein eigenes Kontingent gegenüber
# `gtx` (translate.googleapis.com) und ist die erste Wahl.
ENDPOINTS = (
    ("https://translate.google.com/translate_a/single", "at"),
    ("https://translate.googleapis.com/translate_a/single", "gtx"),
)
# Größere Batches = weniger Requests; der öffentliche Endpunkt drosselt
# aggressiv (HTTP 429). 3000 Zeichen sind unkritisch und halbieren die
# Request-Zahl.
BATCH_CHARS = 2000
MAX_RETRIES = 8
# Mindestabstand zwischen zwei Requests (Sekunden) — ohne ihn kommt nach
# ~30 Anfragen ein 429, das den ganzen Lauf abbricht.
REQUEST_DELAY = 0.8

PLACEHOLDER = re.compile(r"%(?:\d+\$)?[-+ 0#]*\d*(?:\.\d+)?[a-zA-Z]")
TOKEN = "⟦{}⟧"

# Eigennamen, die in keiner Sprache übersetzt werden dürfen.
BRANDS = (
    "AevumPcTracker",
    "Garmin Connect",
    "Health Connect",
    "Digital Balance",
    "PC Tracker",
    "OpenStreetMap",
    "Aevum",
    "Garmin",
    "Android",
)

# ── Kuratierte Kurztexte ────────────────────────────────────────────────
# Maschinelle Übersetzung ist bei EINZELWÖRTERN und kurzen Imperativen
# unzuverlässig („Let's go" wird zu „vamos", „Your day" zu „tu dia" ohne
# Akzent). Genau diese Texte sind aber die sichtbarsten der App — Buttons,
# Schritt-Zähler, Seiten-Überschriften. Sie sind hier fest hinterlegt.
# Schlüssel = language_code → (string_name → Text).
OVERRIDES: dict[str, dict[str, str]] = {
    "es": {
        "ob_lang_eyebrow": "Bienvenido",
        "ob_focus_eyebrow": "Tu día",
        "ob_mascot_eyebrow": "Compañía",
        "ob_perm_eyebrow": "Permisos",
        "ob_trust_eyebrow": "Una última cosa",
        "ob_back": "Atrás",
        "ob_next": "Continuar",
        "ob_skip": "Omitir",
        "ob_step": "Paso %1$d de %2$d",
        "ob_trust_start": "Empezar",
        "ob_focus_add_confirm": "Añadir",
        "ob_focus_add_cancel": "Cancelar",
        "ob_focus_selected": "%1$d de %2$d seleccionados",
        "ob_perm_selected": "%1$d de %2$d activos",
        "ob_mascot_on": "Sí, ven",
        "ob_mascot_off": "Sin mascota",
    },
    "fr": {
        "ob_lang_eyebrow": "Bienvenue",
        "ob_focus_eyebrow": "Ta journée",
        "ob_mascot_eyebrow": "Compagnie",
        "ob_perm_eyebrow": "Autorisations",
        "ob_trust_eyebrow": "Une dernière chose",
        "ob_back": "Retour",
        "ob_next": "Continuer",
        "ob_skip": "Passer",
        "ob_step": "Étape %1$d sur %2$d",
        "ob_trust_start": "C’est parti",
        "ob_focus_add_confirm": "Ajouter",
        "ob_focus_add_cancel": "Annuler",
        "ob_focus_selected": "%1$d sur %2$d sélectionnés",
        "ob_perm_selected": "%1$d sur %2$d activés",
        "ob_mascot_on": "Oui, viens",
        "ob_mascot_off": "Sans mascotte",
    },
    "pt": {
        "ob_lang_eyebrow": "Bem-vindo",
        "ob_focus_eyebrow": "O teu dia",
        "ob_mascot_eyebrow": "Companhia",
        "ob_perm_eyebrow": "Permissões",
        "ob_trust_eyebrow": "Uma última coisa",
        "ob_back": "Voltar",
        "ob_next": "Continuar",
        "ob_skip": "Saltar",
        "ob_step": "Passo %1$d de %2$d",
        "ob_trust_start": "Vamos lá",
        "ob_focus_add_confirm": "Adicionar",
        "ob_focus_add_cancel": "Cancelar",
        "ob_focus_selected": "%1$d de %2$d selecionados",
        "ob_perm_selected": "%1$d de %2$d ativos",
        "ob_mascot_on": "Sim, vem",
        "ob_mascot_off": "Sem mascote",
    },
    "it": {
        "ob_lang_eyebrow": "Benvenuto",
        "ob_focus_eyebrow": "La tua giornata",
        "ob_mascot_eyebrow": "Compagnia",
        "ob_perm_eyebrow": "Autorizzazioni",
        "ob_trust_eyebrow": "Un’ultima cosa",
        "ob_back": "Indietro",
        "ob_next": "Continua",
        "ob_skip": "Salta",
        "ob_step": "Passo %1$d di %2$d",
        "ob_trust_start": "Iniziamo",
        "ob_focus_add_confirm": "Aggiungi",
        "ob_focus_add_cancel": "Annulla",
        "ob_focus_selected": "%1$d di %2$d selezionati",
        "ob_perm_selected": "%1$d di %2$d attivi",
        "ob_mascot_on": "Sì, vieni",
        "ob_mascot_off": "Nessuna mascotte",
    },
    "tr": {
        "ob_lang_eyebrow": "Hoş geldin",
        "ob_focus_eyebrow": "Günün",
        "ob_mascot_eyebrow": "Yol arkadaşı",
        "ob_perm_eyebrow": "İzinler",
        "ob_trust_eyebrow": "Son bir şey",
        "ob_back": "Geri",
        "ob_next": "Devam",
        "ob_skip": "Atla",
        "ob_step": "Adım %1$d / %2$d",
        "ob_trust_start": "Başlayalım",
        "ob_focus_add_confirm": "Ekle",
        "ob_focus_add_cancel": "İptal",
        "ob_focus_selected": "%2$d öğeden %1$d tanesi seçili",
        "ob_perm_selected": "%2$d izinden %1$d tanesi açık",
        "ob_mascot_on": "Evet, gel",
        "ob_mascot_off": "Maskot yok",
    },
    "pl": {
        "ob_lang_eyebrow": "Witaj",
        "ob_focus_eyebrow": "Twój dzień",
        "ob_mascot_eyebrow": "Towarzystwo",
        "ob_perm_eyebrow": "Uprawnienia",
        "ob_trust_eyebrow": "Ostatnia rzecz",
        "ob_back": "Wstecz",
        "ob_next": "Dalej",
        "ob_skip": "Pomiń",
        "ob_step": "Krok %1$d z %2$d",
        "ob_trust_start": "Zaczynamy",
        "ob_focus_add_confirm": "Dodaj",
        "ob_focus_add_cancel": "Anuluj",
        "ob_focus_selected": "Wybrano %1$d z %2$d",
        "ob_perm_selected": "Włączono %1$d z %2$d",
        "ob_mascot_on": "Tak, chodź",
        "ob_mascot_off": "Bez maskotki",
    },
    "da": {
        "ob_lang_eyebrow": "Velkommen",
        "ob_focus_eyebrow": "Din dag",
        "ob_mascot_eyebrow": "Selskab",
        "ob_perm_eyebrow": "Tilladelser",
        "ob_trust_eyebrow": "En sidste ting",
        "ob_back": "Tilbage",
        "ob_next": "Fortsæt",
        "ob_skip": "Spring over",
        "ob_step": "Trin %1$d af %2$d",
        "ob_trust_start": "Lad os begynde",
        "ob_focus_add_confirm": "Tilføj",
        "ob_focus_add_cancel": "Annuller",
        "ob_focus_selected": "%1$d af %2$d valgt",
        "ob_perm_selected": "%1$d af %2$d slået til",
        "ob_mascot_on": "Ja, kom med",
        "ob_mascot_off": "Ingen maskot",
    },
    "nb": {
        "ob_lang_eyebrow": "Velkommen",
        "ob_focus_eyebrow": "Dagen din",
        "ob_mascot_eyebrow": "Følge",
        "ob_perm_eyebrow": "Tillatelser",
        "ob_trust_eyebrow": "En siste ting",
        "ob_back": "Tilbake",
        "ob_next": "Fortsett",
        "ob_skip": "Hopp over",
        "ob_step": "Steg %1$d av %2$d",
        "ob_trust_start": "La oss begynne",
        "ob_focus_add_confirm": "Legg til",
        "ob_focus_add_cancel": "Avbryt",
        "ob_focus_selected": "%1$d av %2$d valgt",
        "ob_perm_selected": "%1$d av %2$d slått på",
        "ob_mascot_on": "Ja, bli med",
        "ob_mascot_off": "Ingen maskot",
    },
    "sv": {
        "ob_lang_eyebrow": "Välkommen",
        "ob_focus_eyebrow": "Din dag",
        "ob_mascot_eyebrow": "Sällskap",
        "ob_perm_eyebrow": "Behörigheter",
        "ob_trust_eyebrow": "En sista sak",
        "ob_back": "Tillbaka",
        "ob_next": "Fortsätt",
        "ob_skip": "Hoppa över",
        "ob_step": "Steg %1$d av %2$d",
        "ob_trust_start": "Nu börjar vi",
        "ob_focus_add_confirm": "Lägg till",
        "ob_focus_add_cancel": "Avbryt",
        "ob_focus_selected": "%1$d av %2$d valda",
        "ob_perm_selected": "%1$d av %2$d påslagna",
        "ob_mascot_on": "Ja, följ med",
        "ob_mascot_off": "Ingen maskot",
    },
}



class Translator:
    def __init__(self, target: str, cache_path: Path, source_language: str = "de"):
        self.target = target
        self.source_language = source_language
        self.cache_path = cache_path
        self.cache: dict[str, str] = {}
        if cache_path.exists():
            try:
                self.cache = json.loads(cache_path.read_text(encoding="utf-8"))
            except Exception:
                self.cache = {}
        self.requests = 0
        self.endpoint_used: dict[str, int] = {}

    def save(self) -> None:
        self.cache_path.parent.mkdir(parents=True, exist_ok=True)
        self.cache_path.write_text(
            json.dumps(self.cache, ensure_ascii=False, indent=0), encoding="utf-8"
        )

    # ── Schutz von Platzhaltern / Escapes ────────────────────────────────
    @staticmethod
    def protect(text: str) -> tuple[str, dict[str, str]]:
        tokens: dict[str, str] = {}

        def repl(match: re.Match[str]) -> str:
            key = TOKEN.format(len(tokens))
            tokens[key] = match.group(0)
            return key

        protected = PLACEHOLDER.sub(repl, text)
        # Eigennamen parken, damit sie unübersetzt bleiben.
        for brand in BRANDS:
            while brand in protected:
                key = TOKEN.format(len(tokens))
                tokens[key] = brand
                protected = protected.replace(brand, key, 1)
        # `\n` steht in der Datei als zwei Zeichen (Backslash + n).
        for literal in ("\\n", "\\t"):
            while literal in protected:
                key = TOKEN.format(len(tokens))
                tokens[key] = literal
                protected = protected.replace(literal, key, 1)
        return protected, tokens

    @staticmethod
    def restore(text: str, tokens: dict[str, str]) -> str:
        for key, value in tokens.items():
            text = text.replace(key, value)
        return text

    # ── HTTP ─────────────────────────────────────────────────────────────
    def _request(self, payload: str) -> list[str]:
        """
        Übersetzt einen Batch — ausschließlich per GET (POST liefert 429).

        Die Endpunkte werden der Reihe nach probiert; erst wenn ALLE
        drosseln, wird gewartet.
        """
        last_error: Exception | None = None
        for attempt in range(MAX_RETRIES):
            for endpoint, client in ENDPOINTS:
                query = urllib.parse.urlencode(
                    {
                        "client": client,
                        "sl": self.source_language,
                        "tl": self.target,
                        "dt": "t",
                        "q": payload,
                    }
                )
                try:
                    request = urllib.request.Request(
                        f"{endpoint}?{query}",
                        headers={"User-Agent": "Mozilla/5.0 (Aevum resource translator)"},
                    )
                    with urllib.request.urlopen(request, timeout=40) as response:
                        raw = response.read().decode("utf-8")
                    time.sleep(REQUEST_DELAY)
                    parsed = json.loads(raw)
                    # parsed[0] = Liste von Segmenten [translated, original, …]
                    translated = "".join(seg[0] for seg in parsed[0] if seg and seg[0])
                    self.requests += 1
                    self.endpoint_used[client] = self.endpoint_used.get(client, 0) + 1
                    return translated.split("\n")
                except Exception as error:  # noqa: BLE001 — Netzfehler sind hier normal
                    last_error = error
            # Beide Endpunkte haben abgelehnt / das Kontingent ist erschöpft.
            wait = min(90, 8 * (2 ** attempt))
            print(f"  {self.target}: Drosselung, warte {wait}s", flush=True)
            time.sleep(wait)
        raise RuntimeError(f"Translate fehlgeschlagen ({self.target}): {last_error}")

    def translate_many(self, texts: list[str]) -> list[str]:
        """Übersetzt eine Liste; behält die Reihenfolge, cached Ergebnisse."""
        result: list[str | None] = [self.cache.get(t) for t in texts]
        pending: list[tuple[int, str]] = [
            (index, text) for index, text in enumerate(texts) if result[index] is None
        ]
        batch: list[tuple[int, str]] = []
        batch_chars = 0

        def flush() -> None:
            nonlocal batch, batch_chars
            if not batch:
                return
            lines = [self.protect(text)[0] for _, text in batch]
            output = self._request("\n".join(lines))
            if len(output) != len(lines):
                # Zeilenumbrüche wurden anders gruppiert → einzeln nachziehen.
                output = []
                for line in lines:
                    single = self._request(line)
                    output.append("\n".join(single))
            for (index, source), translated in zip(batch, output):
                value = translated.strip("\n")
                self.cache[source] = value
                result[index] = value
            batch = []
            batch_chars = 0

        for index, text in pending:
            if batch_chars + len(text) > BATCH_CHARS:
                flush()
            batch.append((index, text))
            batch_chars += len(text) + 1
        flush()
        self.save()
        return [value if value is not None else "" for value in result]


_EXISTING_ESCAPE = re.compile(r"\\[nt'\"\\@?]")


def escape_android(value: str) -> str:
    """
    Text (so wie ihn der XML-Parser liefert) → gültiger Android-String-Inhalt.

    Bereits vorhandene Escapes (`\\n`, `\\'`, `\\\\` …) werden geparkt und
    unverändert zurückgesetzt. Nur ECHTE Backslashes, `&`, `<`, `>` und
    Apostrophe werden escaped — sonst würde aus dem Zeilenumbruch `\\n`
    ein sichtbares `\\\\n`.
    """
    parked: list[str] = []

    def park(match: re.Match[str]) -> str:
        parked.append(match.group(0))
        return f"\x00{len(parked) - 1}\x00"

    value = _EXISTING_ESCAPE.sub(park, value)
    value = value.replace("\\", "\\\\")
    value = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    value = value.replace("'", "\\'").replace('"', '\\"')
    for index, escape in enumerate(parked):
        value = value.replace(f"\x00{index}\x00", escape)
    if value.startswith("@"):
        value = "\\" + value
    return value


def read_strings(path: Path) -> list[tuple[str, str, bool]]:
    """[(name, text, formatted_false)] in Dateireihenfolge."""
    root = ET.parse(path).getroot()
    entries: list[tuple[str, str, bool]] = []
    for element in root:
        if element.tag != "string":
            continue
        name = element.attrib.get("name")
        if not name:
            continue
        formatted = element.attrib.get("formatted", "true") != "false"
        entries.append((name, element.text or "", formatted))
    return entries


def translate_file(
    translator: Translator,
    source: Path,
    target_dir: Path,
    lang: str,
    pivot: dict[str, str] | None = None,
) -> int:
    """
    Übersetzt eine Ressourcendatei.

    [source] ist die englische Datei (Struktur, Keys, Fallback-Texte).
    [pivot] ist die deutsche Fassung derselben Datei: Sie ist die
    Übersetzungsquelle, weil sie die Anrede der App („du") konsistent trägt.
    Ohne sie würde eine maschinelle Übersetzung aus dem Englischen je nach
    Satz zwischen „tú" und „usted" springen — im selben Dialog.
    """
    entries = read_strings(source)
    pivot = pivot or {}

    to_translate: list[str] = []
    translate_index: list[int] = []
    for index, (name, text, _) in enumerate(entries):
        if name in DO_NOT_TRANSLATE or name.startswith(DO_NOT_TRANSLATE_PREFIX):
            continue
        candidate = pivot.get(name) or text
        if not candidate.strip():
            continue
        to_translate.append(candidate)
        translate_index.append(index)

    translated = translator.translate_many(to_translate) if to_translate else []

    values: dict[str, str] = {name: text for name, text, _ in entries}
    for index, value in zip(translate_index, translated):
        name, original, _ = entries[index]
        source_text = pivot.get(name) or original
        protected, tokens = Translator.protect(source_text)
        restored = Translator.restore(value, tokens)
        # Sicherheitsnetz: fehlt ein Token, bleibt der Quelltext stehen.
        missing = [key for key in tokens if key in protected and key not in value]
        if missing or not restored.strip():
            restored = source_text
        # Kuratierte Kurztexte (Buttons, Überschriften) schlagen die
        # Maschinen-Übersetzung — siehe OVERRIDES.
        restored = OVERRIDES.get(lang, {}).get(name) or restored
        # Satzanfang: maschinelle Übersetzer geben einzelne Phrasen gern
        # klein aus („vamos" statt „Vamos"). Nur anheben, nie absenken.
        if (
            restored
            and source_text[:1].isupper()
            and restored[:1].islower()
        ):
            restored = restored[:1].upper() + restored[1:]
        values[name] = restored

    target_dir.mkdir(parents=True, exist_ok=True)
    out = target_dir / source.name
    lines = [f'<?xml version="1.0" encoding="utf-8"?>', "<resources>"]
    lines.append(
        f"    <!-- Maschinell übersetzt (M34) — Quelle: values-de/{source.name}, "
        f"Struktur: values/{source.name}. Platzhalter und Escapes geprüft. -->"
    )
    for name, _, formatted in entries:
        text = values[name]
        attribute = ' formatted="false"' if not formatted else ""
        lines.append(f'    <string name="{name}"{attribute}>{escape_android(text)}</string>')
    lines.append("</resources>")
    lines.append("")
    out.write_text("\n".join(lines), encoding="utf-8")
    return len(entries)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--langs", nargs="*", default=[])
    parser.add_argument("--all", action="store_true")
    args = parser.parse_args()

    languages = args.langs or (["es", "fr", "pt", "it", "tr", "pl", "da", "nb", "sv"] if args.all else [])
    if not languages:
        parser.error("--langs oder --all angeben")

    cache_dir = Path(__file__).resolve().parent / ".translation-cache"
    sources = sorted(SOURCE_DIR.glob("*.xml"))
    sources = [s for s in sources if s.name.startswith("strings")]

    for lang in languages:
        translator = Translator(lang, cache_dir / f"{lang}.json")
        total = 0
        for source in sources:
            pivot_file = RES / "values-de" / source.name
            pivot = dict((n, t) for n, t, _ in read_strings(pivot_file)) if pivot_file.exists() else {}
            count = translate_file(translator, source, RES / f"values-{lang}", lang, pivot)
            total += count
        print(f"{lang}: {total} Strings aus {len(sources)} Dateien, {translator.requests} Requests")
        translator.save()
    return 0


if __name__ == "__main__":
    sys.exit(main())
