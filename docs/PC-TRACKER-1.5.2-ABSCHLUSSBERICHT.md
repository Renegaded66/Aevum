# Abschlussbericht — PC-Tracker 1.5.2: Vorrang hält Sendelücken aus + große Live-Timer

Datum: 2026-10-17 · Befunde aus einer Nutzernachricht (siehe §0)

---

## 0. Der Befund (Zitat)

> „Naja so richtig funktioniert das aber noch nicht, dass das handy vorrang
> hat. Es scheint, so, dass der pc immer ganz kurz aufzeichnet und direkt
> wieder abbricht, aber das jede einzelne minute, vielleicht bei jeder aktiven
> interatkion oder so, keine ahnung, aber auf dem handy habe ich vor 2 stunden
> die activity studium gestartet und auf dem desktop wird das auch angezeigt,
> aber eben irgendwie zeichnet der pc trotzdem jede minute für ganz wenige
> sekunden kurz digital auf und synchroniisert das auch so mit dem handy.
> Außerdem möchte ich im dashboard wirklich irgendwas fancy haben, wo auch
> deutlich die dauer der aufzeichnung sichtbar ist (egal ob handy oder pc
> aufzeichnung), so ähnlich wie halt auch auf dem android dashboard"

Zwei Themen: (1) Vorrang-Pendeln trotz laufender Handy-Aufzeichnung,
(2) große, deutlich sichtbare Dauer-Anzeige im Dashboard.

---

## 1. Diagnose des Pendelns (Code-Evidenz)

Durchgeprüft wurden alle Stationen des Live-Kanals:

- **Handy-Seite:** Der Relay-Job sendet alle 7 s (Screen an) bzw. 15 s
  (Screen aus); Fehlversuche kosten 3–11 s (Connect/Read-Timeout); nach
  Prozess-Kills läuft er beim nächsten Service-Start wieder an. Das
  Grundverhalten ist korrekt — aber jede Sendelücke (Standby, WLAN-Schlaf,
  Service-Neustart, Funkloch) ist real und nicht vollständig vermeidbar.
- **PC-Seite:** Empfang stempelt jede Meldung mit Server-Zeit
  (`HandleRelay`); die Zustandsbewertung galt aber **schon nach 45 s ohne
  Meldung als „nicht mehr aufzeichnend"** — und die Engine startete dann
  sofort die allgemeine Aufzeichnung (Digital), während die nächste
  Handy-Meldung sie unmittelbar wieder beendete. **Ergebnis: kurze
  Digital-Sessions im Minutentakt — exakt Devons Beobachtung.** Jede dieser
  Sessions wurde gespeichert und beim nächsten Sync ans Handy geliefert.

**Fazit:** Die 45-s-Ungeduld war die Fehlkonstruktion — egal aus welchem
Grund die Meldungen stockten, der PC pendelte in Falsch-Aufzeichnungen.

## 2. Fixes (PC-Tracker 1.5.2)

1. **Prioritätsfenster 5 Minuten** (`PhoneLiveRegistry.PriorityWindowMs`):
   Für den Vorrang gilt eine gemeldete Handy-Aufzeichnung erst als beendet,
   wenn 5 Minuten keine Meldung kam. Die UI zeigt die strengere 45-s-Frische
   unverändert ehrlich an (Steuerungs-Knöpfe, „Letzte Meldung …").
2. **Mini-Artefakt-Filter** (`SessionEngine.MinSavedPreemptionMs = 2 s`):
   Vom Vorrang beendete Sessions unter 2 s werden nicht gespeichert — der
   Grenzfall „PC startete Sekunden vor der Handy-Meldung" hinterlässt keinen
   Müll mehr in Verlauf oder Handy-Sync.
3. **Übergangs-Logging** (`phone-priority.log`): Jeder Wechsel „Handy
   erkannt" / „PC wieder frei" wird mit Age der letzten Meldung protokolliert.
4. **Dashboard-Timer (fancy):** Große tickende Dauer (Monospace, türkis)
   auf beiden Karten — PC-Aufzeichnung und Handy-Aufzeichnung („egal ob
   handy oder pc"). Bei Pause friert der Timer ein. Dazu „Letzte Meldung
   vor X" auf der Handy-Karte.

## 3. Verifikation

- **Unit:** 134/134 grün (+3: Prioritätsfenster, explizites Ende gibt frei,
  Artefakt-Filter).
- **Xvfb-Livetest (mit simuliertem Handy):**
  - Meldung „Studium, start vor 2 h" → Vorrang-Karte „PHONE GOES FIRST";
    Handy-Timer zeigte live **2:02:13** (Screenshot).
  - **65 s Funkstille** (> alte 45-s-Grenze) bei AKTIVEM General-Toggle und
    dauerhaft „aktivem" PC (Linux-Idle=0): **0 offene PC-Sessions** — die
    alte Version hätte hier zwingend Digital aufgezeichnet. Die Handy-Karte
    zeigte dabei ehrlich „Last update 1 min ago" + **bleibenden Vorrang**.
  - Session-Ende gemeldet → Log „PC wieder frei"; die allgemeine
    Aufzeichnung startete korrekt wieder (Digital-Session offen).
  - Manuelle Session (Arbeit) → Live-Karte mit **RECORDING-Badge, Türkis-
    Rahmen und großem Timer 00:07** (Screenshot).

## 4. Ehrliche Abgrenzungen

- **Warum die Meldungen bei Devon effektiv spärlich waren** (Standby, WLAN,
  Service-Lebensdauer), ließ sich von hier nicht isolieren — die Handy-Seite
  ist strukturell korrekt und sendet alle 7–15 s. Das 5-Minuten-Fenster
  macht den PC gegen ALLE diese Ursachen robust; sollte es je wieder
  auffallen, steht im `phone-priority.log` exakt, wann das Signal abriss.
- **Karenz statt Sofort-Freiheit:** Nach einem echten Handy-Absturz pausiert
  der PC jetzt bis zu 5 Minuten länger, bevor er wieder selbst aufzeichnen
  darf — bewusster Kompromiss zugunsten von Devons Kernregel („Handy hat
  Vorrang, immer nur eine Aufzeichnung").
- Timer-Anzeige bei Pause: friert auf dem letzten Laufstand ein (die exakte
  Pausen-Spanne liefert der Live-Kanal nicht).

## 5. Release

- Git-Tag `pc-tracker-1.5.2`; GitHub-Release mit Asset
  `AevumPcTracker-1.5.2-win-x64.zip`; Auslieferungsserver + Download-Seite
  aktualisiert (Details `docs/CHANGELOG.md`).
