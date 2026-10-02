# M18.143 — Fremden Contributor aus der Historie entfernt

**Datum:** 2. Oktober 2026
**Art:** Repository-Historie umgeschrieben (kein App-Code betroffen)

---

## Ausgangslage

Devon fragte, warum **Rafa-Ross** als Contributor im Aevum-Repo steht — ein ihm
unbekannter Account.

### Befund

- Rafa-Ross ist ein **fremder GitHub-Account** (User-ID 279471146, erstellt
  26.04.2026, 1 eigenes Repo „Endenan", 17 Follower).
- Er hat **nicht** an Aevum gearbeitet. Ihm wurde genau **ein** Commit
  zugeschrieben: `e49d43d` (M18.134, Fahrrad-Fix).
- Ursache: Der Commit trug die Autor-E-Mail `hermes@nousresearch.com`. GitHub
  ordnet Commits **primär über die Autor-E-Mail** einem Account zu — und dieser
  Account hat `hermes@nousresearch.com` als **verifizierte E-Mail** im Profil
  eingetragen.
- Folge: Jeder Commit mit dieser Adresse landet in Rafa-Ross' Profil. Eine
  GitHub-Suche nach `author-email:hermes@nousresearch.com` ergibt
  **29.557 Commits**, alle demselben Account zugeschrieben — ein bekanntes
  Muster, bei dem beliebte Bot-/Agent-Adressen „gekapert" werden, um in
  Contributor-Listen aufzutauchen.
- Entstanden ist der Commit durch die Kanban-Automatisierung `t_a860c07f`, die
  ihre eigene Git-Identity per `-c user.name= -c user.email=` mitgeschickt
  hatte, statt Devons `.gitconfig` zu verwenden.

---

## Durchführung

### Vorbedingungen (Sicherheitsnetz)

1. **Voll-Bundle** aller Refs:
   `/root/builds/backups/aevum-pre-mailfix-20261002-134540.bundle` (2,7 MB)
2. **Backup-Branch** `backup/pre-mailfix-20261002` mit der alten Historie,
   zusätzlich als eigenes Bundle gesichert.
3. **Ausgangs-Tree-Hash** festgehalten: `0edd238c8bbf892d7bf969856d364585741f40ac`
   — damit ist nachprüfbar, dass kein Code verloren ging.
4. Frischer Clone (`--no-single-branch`), damit die laufenden
   Kanban-Worktrees unangetastet bleiben.

### Umschreiben

Mit `git filter-repo` (2.47.0) und einem E-Mail-Callback:

```
git filter-repo --email-callback \
  'return email.replace(b"hermes@nousresearch.com", b"drostedevon@gmail.com")'
```

Nur die **E-Mail-Adresse** wurde ersetzt. Name (`Hermes Agent`), Datum,
Commit-Message, Diff und Reihenfolge blieben unverändert. Die Commit-SHAs
ändern sich zwangsläufig (der SHA hängt am Inhalt inkl. Autor-Metadaten).

### Gepusht (7 Branches, force-with-lease)

| Branch | alt | neu |
|---|---|---|
| `main` | `4d595e3` | `51f541e` |
| `hermes/t_a860c07f-bicycle-context` | `e49d43d` | `4518c31` |
| `hermes/t_13e9f843-all-activities` | `a226b10` | `8e4e9a5` |
| `hermes/t_386782be-integration` | `209bf23` | `7fdb078` |
| `hermes/t_70a06809-expand-toggle` | `e06cdbe` | `89c5ad1` |
| `hermes/t_88146697-bicycle-tests` | `2e9d819` | `8bdb077` |
| `hermes/t_8e2889cd-bicycle-exit-gap` | `eb8bd91` | `4199ecd` |

### Nachziehen im Arbeits-Repo

`git reset --hard origin/main` plus `update-ref` für die sechs Task-Branches —
**erst nach Inhaltsvergleich**: Die Tree-Hashes aller sechs Branches waren vor
und nach dem Rewrite identisch, ein Reset konnte also nichts verlieren.

---

## Nachweis, dass kein Code verloren ging

| Prüfung | Ergebnis |
|---|---|
| `main`-Tree-Hash vor ↔ nach | `0edd238c8bbf` == `0edd238c8bbf` ✅ |
| Tree-Hashes der 6 Task-Branches | alle identisch ✅ |
| Commit-Anzahl `main` | 328 (unverändert) ✅ |
| Diff-Umfang | nur Autor-E-Mail; Diffs unberührt ✅ |
| `git fsck` | keine Fehler (nur erwartete dangling objects) ✅ |
| Gradle-Compile nach dem Rewrite | BUILD SUCCESSFUL ✅ |
| Working Tree | clean, keine ungepushten Commits ✅ |

**Zusätzliche Prüfung auf Vollständigkeit:** Alle 15 Remote-Branches wurden
einzeln darauf geprüft, ob sie den alten Commit noch enthalten — **kein
einziger**. Eine spätere Push-Operation kann die alte Historie also nicht
zurückholen.

---

## Ergebnis

- **`commits?author=Rafa-Ross` → 0 Treffer** (GitHub-Commit-Index ist live
  sauber)
- **Contributor-Graph-HTML → 0 Treffer für „rafa"** (nur noch Renegaded66 und
  Copilot)
- Der neue Commit `4518c31` (M18.134) ist **Renegaded66** zugeordnet.
- Die **API-Liste** `/contributors` zeigte Rafa-Ross zum Prüfzeitpunkt noch
  einmalig mit 1 Commit — das ist ein CDN-/Backend-Cache (`cache-control:
  max-age=60`, `s-maxage=60`), der getrennt vom Commit-Index aktualisiert wird.
  Die maßgebliche Anzeige (Graph-Seite + Commit-Zuordnung) ist korrekt.

**Hinweis zur endgültigen Bereinigung:** GitHub behält umgeschriebene Commits
eine Zeit lang als „dangling objects" vor (erreichbar per direktem SHA-URL,
aber von keinem Branch mehr aus). Sie verschwinden mit der automatischen
Garbage Collection von GitHub. Vollständig sofort entfernen lässt sich das nur
über den GitHub-Support.

**Und die nachhaltige Seite:** Die Zuordnung selbst kann dauerhaft nur
Rafa-Ross auflösen, indem er `hermes@nousresearch.com` aus seinem Profil
entfernt. Für dieses Repo ist das Problem aber behoben — und für künftige
Commits gilt die Regel, die Identity immer explizit auf Devon Droste zu setzen.

---

## Dauerhafte Absicherung

In der Agent-Memory hinterlegt:

> Git-Commits: IMMER Devon Droste <drostedevon@gmail.com> — nie
> hermes@nousresearch.com (GitHub attribuiert diese E-Mail dem fremden Account
> Rafa-Ross).

Zusätzlich auffällig: Im Repo existieren weitere Automatik-Identitäten
(`hermes-agent@local`, `hermes@local`, `coding@hermes.local`,
`hermes@localhost`, `kanban@local`). Diese sind **unproblematisch**, weil sie
keinem fremden GitHub-Account zugeordnet sind — sie erscheinen als anonyme
Einträge. Nur `hermes@nousresearch.com` war das Problem.

---

## Geänderte Dateien

| Datei | Änderung |
|---|---|
| `docs/M18-143-ABSCHLUSSBERICHT.md` | **neu** |
| Git-Historie | Autor-E-Mail in 1 Commit; 7 Branches neu gepusht |
| App-Code | **keine Änderung** |

Version bleibt **1.0.22 (versionCode 23)** — es gab keine Code-Änderung, ein
erneuter Upload ist nicht nötig.
