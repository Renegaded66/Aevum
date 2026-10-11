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
/// </summary>
public sealed class SessionEngine
{
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

        // „Sichtbar“ hängt vom Regel-Modus ab: Vordergrund ODER (im Modus
        // „läuft im Hintergrund“) der laufende Prozess.
        bool SeenNow(TrackedSession s) =>
            t.Foreground?.ProgramKey == s.ProgramKey
            || (ruleMap.TryGetValue(s.ProgramKey, out var r)
                && r.Mode == RecordMode.WhileRunning
                && running.Contains(s.ProgramKey));

        // 1) Laufende Session pflegen.
        if (Active is not null)
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
        if (Active is not null && idleBlocked)
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
        if (Active is not null && !SeenNow(Active))
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

        // 5) Neue Session starten.
        if (Active is null && !t.Paused && !idleBlocked)
        {
            // 5a) Vordergrund-Programm mit Regel hat Vorrang.
            if (t.Foreground is not null && ruleMap.TryGetValue(t.Foreground.ProgramKey, out _))
            {
                Start(t, t.Foreground.ProgramKey, t.Foreground.DisplayName,
                    _fgSinceMs > 0 ? Math.Min(_fgSinceMs, t.NowMs) : t.NowMs, upserts);
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
                    var startAt = Math.Max(pickSince, _lastSessionEndMs);
                    startAt = Math.Min(startAt, t.NowMs);
                    Start(t, pick, ProgramNameFor(t, pick), startAt, upserts);
                }
            }
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
