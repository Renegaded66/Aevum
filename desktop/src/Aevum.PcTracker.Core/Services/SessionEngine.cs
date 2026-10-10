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
///  • Wechsel direkt zu einem anderen zugeordneten Programm: die neue Session
///    startet, sobald die alte abgeschlossen ist — ihr Start liegt aber auf der
///    ersten Sicht, sodass keine Zeit verloren geht.
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

    /// <summary>
    /// Setzt die Zählung für ein gerade sichtbares Programm zurück — der Host ruft
    /// das nach Regel-Änderungen auf, damit eine neu angelegte Regel nicht
    /// rückwirkend die ganze bisherige Sichtbarkeit aufzeichnet.
    /// </summary>
    public void NotifyRulesChanged(long nowMs)
    {
        if (Active is null && _fgKey is not null)
        {
            _fgSinceMs = nowMs;
        }
    }

    /// <summary>Beendet eine laufende Session sofort (Nutzer drückt „Pause").</summary>
    public IReadOnlyList<TrackedSession> Pause(long nowMs)
    {
        _ = nowMs;
        _paused = true;
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
        // nicht auf das getrackte Programm — „jetzt" wäre zu großzügig).
        var ended = Finalize(Active, Active.LastSeenAtMs);
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

        // Nach einer Pause zählt die Sichtbarkeit erst ab jetzt.
        var wasPaused = _paused;
        _paused = t.Paused;
        if (wasPaused && !t.Paused)
        {
            _fgSinceMs = t.NowMs;
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

        // 1) Laufende Session pflegen.
        if (Active is not null)
        {
            if (!ruleMap.ContainsKey(Active.ProgramKey))
            {
                // Regel gelöscht oder deaktiviert → Session endet zur letzten Sicht.
                upserts.Add(Finalize(Active, Active.LastSeenAtMs));
                Active = null;
            }
            else if (t.Foreground?.ProgramKey == Active.ProgramKey)
            {
                Active = Active with { LastSeenAtMs = t.NowMs };
                upserts.Add(Active);
            }
        }

        // 2) Inaktivität beendet die Session (Zeitpunkt der letzten Eingabe).
        if (Active is not null && idleBlocked)
        {
            long endAt;
            if (t.Foreground?.ProgramKey == Active.ProgramKey)
            {
                endAt = t.NowMs - t.IdleMs; // letzter Input
            }
            else
            {
                endAt = Active.LastSeenAtMs; // Programm war ohnehin schon hinten
            }
            upserts.Add(Finalize(Active, endAt));
            Active = null;
        }

        // Zurückkommen nach einer Idle-Phase: Die Zählung beginnt JETZT —
        // so startet die nächste Session mit dem Moment des Aufwachens und
        // nicht rückwirkend mit dem alten „erste Sicht"-Zeitpunkt.
        if (!idleBlocked && _wasIdleBlocked && Active is null)
        {
            _fgSinceMs = t.NowMs;
        }
        _wasIdleBlocked = idleBlocked;

        // 3) Threshold-Ende: Programm ist länger weg als sein Threshold.
        if (Active is not null && t.Foreground?.ProgramKey != Active.ProgramKey)
        {
            var rule = ruleMap[Active.ProgramKey];
            var thresholdMs = Math.Max(1, rule.ThresholdSeconds) * 1000L;
            if (t.NowMs - Active.LastSeenAtMs >= thresholdMs)
            {
                upserts.Add(Finalize(Active, Active.LastSeenAtMs));
                Active = null;
            }
        }

        // 4) Neue Session starten.
        if (Active is null && !t.Paused && !idleBlocked && t.Foreground is not null
            && ruleMap.TryGetValue(t.Foreground.ProgramKey, out var startRule))
        {
            long startAt = _fgSinceMs > 0 ? Math.Min(_fgSinceMs, t.NowMs) : t.NowMs;
            Active = new TrackedSession
            {
                Id = _idFactory(),
                ProgramKey = t.Foreground.ProgramKey,
                ProgramName = t.Foreground.DisplayName,
                ActivityKey = startRule.ActivityKey,
                StartAtMs = startAt,
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

    private static TrackedSession Finalize(TrackedSession s, long endAtMs)
    {
        if (endAtMs < s.StartAtMs)
        {
            endAtMs = s.StartAtMs;
        }
        return s with { EndAtMs = endAtMs };
    }
}
