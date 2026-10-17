using Aevum.PcTracker.Core.Models;

namespace Aevum.PcTracker.Core.Services;

/// <summary>Ein Eingang für einen Engine-Tick (alle Eingaben explizit — reine Logik, testbar).</summary>
public sealed record EngineTick
{
    public required long NowMs { get; init; }
    public required ForegroundSample? Foreground { get; init; }
    public required IReadOnlyList<TrackingRule> Rules { get; init; }
    public required bool Paused { get; init; }
    public required bool IdleEnabled { get; init; }
    public required long IdleThresholdMs { get; init; }
    /// <summary>Zeit seit der letzten Nutzereingabe (0 = gerade aktiv).</summary>
    public required long IdleMs { get; init; }
    public required string TimezoneId { get; init; }
    /// <summary>Gerade laufende Prozess-Keys (exe, lowercase) — für Regeln im Modus „läuft auch im Hintergrund“.</summary>
    public IReadOnlyList<string> RunningPrograms { get; init; } = Array.Empty<string>();

    // --- Allgemeine Aufzeichnung («General») ---
    /// <summary>Schalter: allgemeine Aufzeichnung aktiv.</summary>
    public bool GeneralEnabled { get; init; }
    /// <summary>Gewählte Aktivität der allgemeinen Aufzeichnung (leer = nichts gewählt).</summary>
    public string GeneralActivityKey { get; init; } = "";
    /// <summary>Spielt gerade Ton ab? Zählt wie eine Nutzereingabe („PC wird benutzt“).</summary>
    public bool AudioActive { get; init; }

    /// <summary>
    /// PC-Tracker 1.5.0: Zeichnet gerade das HANDY auf? Dann hat es Vorrang —
    /// der PC beendet alle eigenen Sessions und startet nichts Neues.
    /// </summary>
    public bool PhoneRecording { get; init; }

    /// <summary>
    /// PC-Tracker 1.6.0: Frisch bestätigt, dass am Handy NICHTS läuft
    /// (Live-Kanal; ohne Handy-Kopplung immer true). Nur dann startet
    /// irgendetwas Neues — ohne Bestätigung bleibt der PC standhaft und
    /// die UI erklärt ehrlich warum („Stand des Handys nicht verifiziert").
    /// Vorgabe true = Gate aus (rückwärtskompatibel für reine Engine-Tests;
    /// der Host setzt den echten Wert bei jedem Tick).
    /// </summary>
    public bool PhoneVerifiedIdle { get; init; } = true;
}

/// <summary>
/// Die Aufzeichnungs-Engine: entscheidet rein aus den Tick-Eingaben, wann eine
/// Session startet, verlängert wird oder endet. Sie hält keinen eigenen
/// Persistenzzustand — der Host wendet die zurückgegebenen Sessions auf den
/// Store an (Upsert). Damit ist jede Regel einzeln testbar.
///
/// Verhaltensregeln (siehe Auftrag):
///  • Öffnen eines zugeordneten Programms  → Session startet (Start = erste Sicht).
///  • Kurzer Wechsel/Alt-Tab innerhalb des Thresholds → Session läuft weiter.
///  • Programm länger weg als Threshold     → Session endet zur letzten Sicht.
///  • Modus „läuft im Hintergrund“: „Sicht“ zählt auch, solange der Prozess
///    läuft (nicht nur im Vordergrund). Eine solche Session wird beendet,
///    sobald ein ANDERES zugeordnetes Programm den Vordergrund übernimmt —
///    und läuft danach (gekappt an das Session-Ende) weiter, wenn es noch offen ist.
///  • Inaktivität (Idle) beendet die Session zum Zeitpunkt der letzten Eingabe.
///  • Pause (Nutzer-Schalter) beendet die laufende Session sofort.
///  • Allgemeine Aufzeichnung: läuft nur, wenn KEINE Regel greift, mit der vom
///    Nutzer gewählten Aktivität; aktiv solange Maus/Tastatur benutzt werden
///    oder Ton läuft; endet nach 1 Minute ohne Aktivität.
///  • Manuelle Aktivität (PC 1.3.0): Der Nutzer startet sie im Dashboard
///    (sofort oder mit Vorlaufzeit). Sie hat VORRANG: eine laufende Session
///    endet zum Startzeitpunkt, Regel- und Allgemein-Starts sind blockiert,
///    Idle beendet sie nicht — beendet wird sie über den Stop-Knopf, die
///    Pause oder das App-Ende (Flush).
///  • Verifikations-Gate (PC 1.6.0): Ohne frische Bestätigung „am Handy läuft
///    nichts" startet nichts Neues; laufende Sessions laufen weiter.
/// </summary>
public sealed class SessionEngine
{
    /// <summary>Programm-Key der allgemeinen Aufzeichnung (kollidiert nie mit exe-Keys).</summary>
    public const string GeneralProgramKey = "__general__";

    /// <summary>
    /// Programm-Key MANUELL gestarteter Aktivitäten (PC 1.3.0, Dashboard „Aktivität
    /// starten"). Devon: „Ich will also auch dort activitys starten können
    /// (entweder direkt oder mit vorlaufzeit)." — bewusst ein eigener Key:
    /// Die Session gehört keinem Programm, sondern dem Nutzer; sie läuft, bis
    /// er sie stoppt (oder Pause/App-Ende sie beendet). Idle beendet sie NICHT.
    /// </summary>
    public const string ManualProgramKey = "__manual__";

    /// <summary>Anzeigename der allgemeinen Aufzeichnung (erscheint in Verlauf/Handy).</summary>
    public const string GeneralProgramName = "General use";

    /// <summary>Anzeigename manuell gestarteter Sessions (erscheint in Verlauf/Handy).</summary>
    public const string ManualProgramName = "Manual start";

    /// <summary>Die allgemeine Aufzeichnung endet nach dieser Ruhezeit (Maus/Tastatur still und kein Ton).</summary>
    public const long GeneralIdleMs = 60_000;

    /// <summary>
    /// PC 1.5.2: Sessions, die der Handy-Vorrang beendet, werden unter dieser
    /// Länge gar nicht gespeichert — nur der Grenzfall „PC startete Sekunden
    /// vor einer Handy-Meldung" fällt darunter, und solche Mini-Schnipsel
    /// sollen weder im Verlauf noch im Handy-Sync auftauchen.
    /// </summary>
    public const long MinSavedPreemptionMs = 2_000;

    private readonly Func<string> _idFactory;

    public SessionEngine(Func<string>? idFactory = null)
        => _idFactory = idFactory ?? (() => Guid.NewGuid().ToString());

    /// <summary>Die aktuell laufende Session (oder null).</summary>
    public TrackedSession? Active { get; private set; }

    private string? _fgKey;
    private long _fgSinceMs;
    private bool _paused;
    private bool _wasIdleBlocked;

    /// <summary>Erste Sicht je Programm-Key, das gerade LÄUFT (Modus „Hintergrund“).</summary>
    private readonly Dictionary<string, long> _runSince = new(StringComparer.OrdinalIgnoreCase);
    private HashSet<string> _lastRunning = new(StringComparer.OrdinalIgnoreCase);

    /// <summary>Ende der zuletzt abgeschlossenen Session (kappt Hintergrund-Neustarts).</summary>
    private long _lastSessionEndMs;

    /// <summary>Letzter Tick, in dem die allgemeine Aufzeichnung als „in Benutzung“ galt.</summary>
    private long _genLastActiveMs;

    /// <summary>
    /// Setzt die Zählung für gerade sichtbare Programme zurück — der Host ruft
    /// das nach Regel-Änderungen auf, damit eine neu angelegte Regel nicht
    /// rückwirkend die ganze bisherige Sichtbarkeit aufzeichnet.
    /// </summary>
    public void NotifyRulesChanged(long nowMs)
    {
        foreach (var key in _lastRunning)
        {
            _runSince[key] = nowMs;
        }
        if (Active is null && _fgKey is not null)
        {
            _fgSinceMs = nowMs;
        }
    }

    /// <summary>Beendet eine laufende Session sofort (Nutzer drückt „Pause“).</summary>
    public IReadOnlyList<TrackedSession> Pause(long nowMs)
    {
        _ = nowMs;
        _paused = true;
        foreach (var key in _lastRunning)
        {
            _runSince[key] = 0; // nach dem Fortsetzen zählt die Sicht neu (Resume-Flanke)
        }
        return Flush();
    }

    /// <summary>
    /// Schließt eine laufende Session ab (Ende = letzte Sicht) — für Programmende
    /// und App-Shutdown. Anders als <see cref="Pause"/> wird kein Pausen-Zustand gesetzt.
    /// </summary>
    public IReadOnlyList<TrackedSession> Flush()
    {
        if (Active is null)
        {
            return Array.Empty<TrackedSession>();
        }
        // Ende = letzte Sicht (die App-Interaktion selbst bewegt den Vordergrund
        // nicht auf das getrackte Programm — „jetzt“ wäre zu großzügig).
        var ended = Finalize(Active, Active.LastSeenAtMs);
        _lastSessionEndMs = ended.EndAtMs ?? 0;
        Active = null;
        return new[] { ended };
    }

    /// <summary>
    /// PC 1.3.0 (M20): Startet eine Aktivität MANUELL — sofort oder nach
    /// Vorlaufzeit (der Countdown läuft im Dashboard; hier kommt nur der
    /// Ablauf an). Vorrang-Regel: Eine laufende Session (Regel wie allgemein)
    /// endet mit dem Startzeitpunkt — der Nutzerentscheid gewinnt. Die manuelle
    /// Session blockiert danach Regel- UND Allgemein-Starts, bis sie gestoppt
    /// wird (Stop-Knopf, Pause oder App-Ende).
    /// </summary>
    public IReadOnlyList<TrackedSession> StartManual(string activityKey, string timezoneId, long nowMs)
    {
        var upserts = new List<TrackedSession>(2);
        if (Active is not null)
        {
            var ended = Finalize(Active, Math.Max(Active.StartAtMs, nowMs));
            _lastSessionEndMs = ended.EndAtMs ?? 0;
            upserts.Add(ended);
            Active = null;
        }
        // Sichtbarkeits-Zählung ab jetzt: Endet die manuelle Session später,
        // startet eine Regel-Session NICHT rückwirkend über sie hinweg (sonst
        // überlappten sich „Arbeit" und die nachlaufende Programm-Session).
        ResetVisibilityClock(nowMs);
        Active = new TrackedSession
        {
            Id = _idFactory(),
            ProgramKey = ManualProgramKey,
            ProgramName = ManualProgramName,
            ActivityKey = activityKey,
            StartAtMs = nowMs,
            LastSeenAtMs = nowMs,
            EndAtMs = null,
            TimezoneId = timezoneId,
            CreatedAtMs = nowMs,
        };
        upserts.Add(Active);
        return upserts;
    }

    /// <summary>
    /// PC 1.3.0 (M20): Beendet die laufende Session SOFORT (Stop-Knopf im
    /// Dashboard, Ende = jetzt). Anders als <see cref="Flush"/> (App-Ende,
    /// Ende = letzte Sicht) erwartet der Nutzer beim Stop-Knopf, dass „jetzt"
    /// das Ende ist.
    /// </summary>
    public IReadOnlyList<TrackedSession> StopActive(long nowMs)
    {
        if (Active is null)
        {
            return Array.Empty<TrackedSession>();
        }
        var ended = Finalize(Active, nowMs);
        _lastSessionEndMs = ended.EndAtMs ?? 0;
        Active = null;
        // Gleiche Logik wie in StartManual: Die nächste Regel-Session zählt ab
        // jetzt, nicht rückwirkend bis zur ersten Sicht des Programms.
        ResetVisibilityClock(nowMs);
        return new[] { ended };
    }

    public IReadOnlyList<TrackedSession> Tick(EngineTick t)
    {
        var upserts = new List<TrackedSession>(2);

        // Vordergrund-Wechsel festhalten (erste Sicht = potenzieller Session-Start).
        if (t.Foreground?.ProgramKey != _fgKey)
        {
            _fgKey = t.Foreground?.ProgramKey;
            _fgSinceMs = t.Foreground is null ? 0 : t.NowMs;
        }

        // Laufende Prozesse pflegen: erste Sicht je „läuft“-Programm festhalten.
        var running = new HashSet<string>(t.RunningPrograms, StringComparer.OrdinalIgnoreCase);
        foreach (var key in running)
        {
            if (!_runSince.ContainsKey(key))
            {
                _runSince[key] = t.NowMs;
            }
        }
        foreach (var key in _runSince.Keys.ToList())
        {
            if (!running.Contains(key))
            {
                _runSince.Remove(key);
            }
        }
        _lastRunning = running;

        // Nach einer Pause zählt die Sichtbarkeit erst ab jetzt.
        var wasPaused = _paused;
        _paused = t.Paused;
        if (wasPaused && !t.Paused)
        {
            _fgSinceMs = t.NowMs;
            foreach (var key in running)
            {
                _runSince[key] = t.NowMs;
            }
        }

        var idleBlocked = t.IdleEnabled && t.IdleMs >= t.IdleThresholdMs;
        var ruleMap = new Dictionary<string, TrackingRule>(StringComparer.OrdinalIgnoreCase);
        foreach (var r in t.Rules)
        {
            if (r.Enabled)
            {
                ruleMap[r.ProgramKey] = r;
            }
        }

        var generalRequested = t.GeneralEnabled && t.GeneralActivityKey.Length > 0;
        var userActive = t.IdleMs < GeneralIdleMs || t.AudioActive;
        if (userActive)
        {
            _genLastActiveMs = t.NowMs;
        }

        // „Sichtbar“ hängt vom Regel-Modus ab: Vordergrund ODER (im Modus
        // „läuft im Hintergrund“) der laufende Prozess.
        bool SeenNow(TrackedSession s) =>
            t.Foreground?.ProgramKey == s.ProgramKey
            || (ruleMap.TryGetValue(s.ProgramKey, out var r)
                && r.Mode == RecordMode.WhileRunning
                && running.Contains(s.ProgramKey));

        // 0) HANDY-VORRANG (PC-Tracker 1.5.0). Devon: „Offensichtlich sollte die
        //    pc aufzeichnung gestoppt werden, wenn etwas am handy beginnt
        //    aufzuzeichnen […] egal ob es nur die allgemeine pc aktivität […]
        //    oder ein bestimmtes programm […] Also bedenke dass immer nur eine
        //    sache gleichzeitig aufgezeichnet werden können soll und handy hat
        //    vorrang."
        //    Solange das Handy aufzeichnet: JEDE PC-Session endet sofort
        //    (Ende = jetzt — auch eine manuelle: der Vorrang ist eine Ansage
        //    des Nutzers, kein Bug), nichts Neues startet, und die
        //    Sichtbarkeits-Zähler beginnen NACH dem Handy-Ende frisch —
        //    sonst würde die nächste PC-Session rückwirkend die ganze
        //    Handy-Zeit beanspruchen. Der Block steht VOR der Session-Pflege
        //    (Schritt 1), damit die gerade beendete Session keinen
        //    Heartbeat-Doppeleintrag mehr bekommt.
        if (t.PhoneRecording)
        {
            if (Active is not null)
            {
                var endedPhone = Finalize(Active, t.NowMs);
                _lastSessionEndMs = endedPhone.EndAtMs ?? 0;
                // PC 1.5.2: Mini-Artefakte (unter 2 s) gar nicht erst speichern.
                // Sie entstehen nur im Grenzfall „PC startete kurz vor einer
                // Handy-Meldung" und sind in Verlauf und Handy-Sync nur Müll.
                // Echte (auch kurze) Aufzeichnungen sind davon unberührt —
                // nur dieser Vorrang-Sonderfall filtert.
                if ((endedPhone.EndAtMs ?? 0) - endedPhone.StartAtMs >= MinSavedPreemptionMs)
                {
                    upserts.Add(endedPhone);
                }
                Active = null;
            }
            ResetVisibilityClock(t.NowMs);
            return upserts;
        }

        // 0b) VERIFIKATIONS-GATE (PC-Tracker 1.6.0). Devon: „Am besten sollte
        //     die desktop app erst erlauben, überhaupt irgendwas aufzuzeichnen
        //     […] wenn die desktop app verifiziert hat, das auf dem handy
        //     nichts läuft, ansonsten kommt eine meldung das der stand des
        //     handys nicht verifiziert werden konnte."
        //     Ohne frische Bestätigung startet NICHTS Neues (weder Regel noch
        //     allgemein). Laufende Sessions bleiben aber am Leben — nur der
        //     frische Handy-Vorrang (Schritt 0) beendet sie; sonst würde eine
        //     verzögerte Sync-Bestätigung eine gesunde PC-Aufzeichnung
        //     abschneiden. Die Sichtbarkeit zählt ab jetzt: Nach der nächsten
        //     Bestätigung startet nichts rückwirkend über die unbestätigte
        //     Zeit (gleiche Semantik wie der Handy-Vorrang).
        if (!t.PhoneVerifiedIdle)
        {
            ResetVisibilityClock(t.NowMs);
        }

        // 1) Laufende Session pflegen. (Die allgemeine Aufzeichnung wird in
        //    Schritt 6 gepflegt, die manuelle hier direkt — beide folgen nicht
        //    den Regel-Thresholds.)
        if (Active is { ProgramKey: ManualProgramKey })
        {
            // M20: Manuell gestartete Aktivität — nur der Nutzer beendet sie
            // (Stop-Knopf/Pause/App-Ende). Der Heartbeat hält last_seen frisch,
            // damit ein Absturz sie am letzten Lebenszeichen schließt.
            Active = Active with { LastSeenAtMs = t.NowMs };
            upserts.Add(Active);
        }
        else if (Active is not null && Active.ProgramKey != GeneralProgramKey)
        {
            if (!ruleMap.ContainsKey(Active.ProgramKey))
            {
                // Regel gelöscht oder deaktiviert → Session endet zur letzten Sicht.
                upserts.Add(Finalize(Active, Active.LastSeenAtMs));
                Active = null;
            }
            else if (SeenNow(Active))
            {
                Active = Active with { LastSeenAtMs = t.NowMs };
                upserts.Add(Active);
            }
        }

        // 2) Inaktivität beendet die Session (Zeitpunkt der letzten Eingabe).
        //    Ausnahmen: Die allgemeine Aufzeichnung hat ihre eigene Ruhe-Logik
        //    (1 Minute bzw. Ton) und wird hier nicht von der globalen Schwelle
        //    beendet; eine MANUELLE Session läuft, bis der Nutzer sie stoppt —
        //    wer „Arbeit" startet, meint es so (Devon: „Hauptsache nichts
        //    anderes wird aufgezeichnet" — seine Ansage gewinnt).
        if (Active is not null && Active.ProgramKey != GeneralProgramKey
            && Active.ProgramKey != ManualProgramKey && idleBlocked)
        {
            var endAt = SeenNow(Active) ? t.NowMs - t.IdleMs : Active.LastSeenAtMs;
            var ended = Finalize(Active, endAt);
            _lastSessionEndMs = ended.EndAtMs ?? 0;
            upserts.Add(ended);
            Active = null;
        }

        // Zurückkommen nach einer Idle-Phase: Die Zählung beginnt JETZT —
        // so startet die nächste Session mit dem Moment des Aufwachens und
        // nicht rückwirkend mit dem alten „erste Sicht“-Zeitpunkt.
        if (!idleBlocked && _wasIdleBlocked && Active is null)
        {
            _fgSinceMs = t.NowMs;
            foreach (var key in running)
            {
                _runSince[key] = t.NowMs;
            }
        }
        _wasIdleBlocked = idleBlocked;

        // 3) Threshold-Ende: Programm ist länger weg als sein Threshold.
        //    (Nur Regel-Sessions — General und manuelle kennen keinen Threshold.)
        if (Active is not null && Active.ProgramKey != GeneralProgramKey
            && Active.ProgramKey != ManualProgramKey && !SeenNow(Active))
        {
            var rule = ruleMap[Active.ProgramKey];
            var thresholdMs = Math.Max(1, rule.ThresholdSeconds) * 1000L;
            if (t.NowMs - Active.LastSeenAtMs >= thresholdMs)
            {
                var ended = Finalize(Active, Active.LastSeenAtMs);
                _lastSessionEndMs = ended.EndAtMs ?? 0;
                upserts.Add(ended);
                Active = null;
            }
        }

        // 4) Vordergrund übernimmt: eine laufende Hintergrund-Session (anderes
        //    Programm) endet jetzt — die neue startet im selben Tick.
        if (Active is not null && t.Foreground is not null
            && t.Foreground.ProgramKey != Active.ProgramKey
            && ruleMap.TryGetValue(t.Foreground.ProgramKey, out var fgRule)
            && ruleMap.TryGetValue(Active.ProgramKey, out var activeRule)
            && activeRule.Mode == RecordMode.WhileRunning)
        {
            _ = fgRule;
            var ended = Finalize(Active, t.NowMs);
            _lastSessionEndMs = ended.EndAtMs ?? 0;
            upserts.Add(ended);
            Active = null;
        }

        // 5) Neue Regel-Session starten. Läuft gerade die allgemeine Aufzeichnung,
        //    endet sie zum Startzeitpunkt der Regel — Regeln haben Vorrang.
        if (Active is null or { ProgramKey: GeneralProgramKey } && !t.Paused && !idleBlocked
            && t.PhoneVerifiedIdle)
        {
            string? startKey = null;
            string startName = "";
            long startAt = t.NowMs;

            // 5a) Vordergrund-Programm mit Regel hat Vorrang.
            if (t.Foreground is not null && ruleMap.TryGetValue(t.Foreground.ProgramKey, out _))
            {
                startKey = t.Foreground.ProgramKey;
                startName = t.Foreground.DisplayName;
                startAt = _fgSinceMs > 0 ? Math.Min(_fgSinceMs, t.NowMs) : t.NowMs;
            }
            else
            {
                // 5b) Läuft ein Hintergrund-Programm mit passender Regel?
                //     (ältestes zuerst; Start an das letzte Session-Ende gekappt)
                string? pick = null;
                long pickSince = 0;
                foreach (var key in running)
                {
                    if (ruleMap.TryGetValue(key, out var rule) && rule.Mode == RecordMode.WhileRunning)
                    {
                        var since = _runSince.TryGetValue(key, out var s) ? s : t.NowMs;
                        if (pick is null || since < pickSince)
                        {
                            pick = key;
                            pickSince = since;
                        }
                    }
                }
                if (pick is not null)
                {
                    startKey = pick;
                    startName = ProgramNameFor(t, pick);
                    startAt = Math.Min(Math.Max(pickSince, _lastSessionEndMs), t.NowMs);
                }
            }

            if (startKey is not null)
            {
                if (Active is { ProgramKey: GeneralProgramKey } general)
                {
                    var endedGeneral = Finalize(general,
                        Math.Max(general.StartAtMs, Math.Min(t.NowMs, startAt)));
                    _lastSessionEndMs = endedGeneral.EndAtMs ?? 0;
                    upserts.Add(endedGeneral);
                    Active = null;
                }
                Start(t, startKey, startName, startAt, upserts);
            }
        }

        // 6) Allgemeine Aufzeichnung: nur wenn keine Regel läuft, mit der gewählten
        //    Aktivität, solange der PC benutzt wird (Maus/Tastatur ODER Ton);
        //    Ende nach 1 Minute ohne Aktivität, beim Ausschalten oder Wechsel.
        if (Active is not null && Active.ProgramKey == GeneralProgramKey)
        {
            var activityChanged = !string.Equals(Active.ActivityKey, t.GeneralActivityKey,
                StringComparison.OrdinalIgnoreCase);
            if (!userActive || !generalRequested || t.Paused || activityChanged)
            {
                // Ende = letzter als aktiv gewerteter Moment (Ton bis zuletzt,
                // sonst die letzte Eingabe) — keine Geister-Minuten.
                var endAt = userActive
                    ? t.NowMs
                    : Math.Max(Active.StartAtMs, Math.Max(t.NowMs - t.IdleMs, _genLastActiveMs));
                var ended = Finalize(Active, endAt);
                _lastSessionEndMs = ended.EndAtMs ?? 0;
                upserts.Add(ended);
                Active = null;
            }
            else
            {
                Active = Active with { LastSeenAtMs = t.NowMs };
                upserts.Add(Active);
            }
        }
        if (Active is null && generalRequested && userActive && !t.Paused && t.PhoneVerifiedIdle)
        {
            Active = new TrackedSession
            {
                Id = _idFactory(),
                ProgramKey = GeneralProgramKey,
                ProgramName = GeneralProgramName,
                ActivityKey = t.GeneralActivityKey,
                StartAtMs = t.NowMs,
                LastSeenAtMs = t.NowMs,
                EndAtMs = null,
                TimezoneId = t.TimezoneId,
                CreatedAtMs = t.NowMs,
            };
            upserts.Add(Active);
        }

        // Pro Session nur den jüngsten Stand ausliefern — ein Heartbeat und ein
        // Idle-Ende im selben Tick ergeben sonst zwei Einträge derselben Session.
        return upserts
            .GroupBy(s => s.Id)
            .Select(g => g.Last())
            .ToList();
    }

    private void Start(EngineTick t, string programKey, string displayName, long startAt, List<TrackedSession> upserts)
    {
        var rule = t.Rules.First(r => string.Equals(r.ProgramKey, programKey, StringComparison.OrdinalIgnoreCase));
        Active = new TrackedSession
        {
            Id = _idFactory(),
            ProgramKey = programKey,
            ProgramName = displayName,
            ActivityKey = rule.ActivityKey,
            StartAtMs = startAt,
            LastSeenAtMs = t.NowMs,
            EndAtMs = null,
            TimezoneId = t.TimezoneId,
            CreatedAtMs = t.NowMs,
        };
        upserts.Add(Active);
    }

    /// <summary>
    /// Setzt die Sichtbarkeits-Zählung auf „jetzt": Nach einem manuellen
    /// Eingriff (Start/Stop) beginnt die nächste Regel-Session mit dem Moment
    /// des Eingriffs statt rückwirkend mit der ersten Sicht des Programms.
    /// </summary>
    private void ResetVisibilityClock(long nowMs)
    {
        _fgSinceMs = nowMs;
        foreach (var key in _lastRunning)
        {
            _runSince[key] = nowMs;
        }
    }

    private static string ProgramNameFor(EngineTick t, string programKey)
        => t.Foreground?.ProgramKey == programKey ? t.Foreground.DisplayName : programKey;

    private static TrackedSession Finalize(TrackedSession s, long endAtMs)
    {
        if (endAtMs < s.StartAtMs)
        {
            endAtMs = s.StartAtMs;
        }
        return s with { EndAtMs = endAtMs };
    }
}
