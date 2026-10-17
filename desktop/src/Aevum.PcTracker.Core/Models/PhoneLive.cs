namespace Aevum.PcTracker.Core.Models;

/// <summary>
/// Der LIVE-Zustand einer am HANDY laufenden Aufzeichnung (PC-Tracker 1.4.0).
/// Die Aevum-App meldet ihn während einer Live-Session alle wenigen Sekunden
/// per <c>POST /api/live</c>; das PC-Dashboard zeigt ihn mit Stop-/Pause-Knopf.
/// </summary>
public sealed record PhoneLiveSession
{
    public required string SessionId { get; init; }
    public required string ActivityKey { get; init; }
    public required string ActivityName { get; init; }
    public string Icon { get; init; } = "";
    public string CategoryKey { get; init; } = "";
    public required long StartAtMs { get; init; }
    /// <summary>M18.62-Semantik der App: „Pause" schließt den Block ab (Session-Split).</summary>
    public required bool IsPaused { get; init; }
    /// <summary>Ende des aktuellen Aufzeichnungsblocks (bei Pause: der Pause-Zeitpunkt; sonst irrelevant/0).</summary>
    public long BlockEndAtMs { get; init; }
    /// <summary>Zeitpunkt, an dem der PC diese Meldung empfangen hat (vom Server gestempelt).</summary>
    public required long LastUpdateMs { get; init; }
}

/// <summary>Ein vom PC ausgelöster Steuerbefehl für die Handy-Session.</summary>
public enum PhoneLiveCommand
{
    Pause = 0,
    Resume = 1,
    Stop = 2,
}

/// <summary>Momentaufnahme fürs Dashboard (reine Werte, UI-tauglich).</summary>
public sealed record PhoneLiveStatus(
    PhoneLiveSession? Session,
    bool Stale,
    PhoneLiveCommand? PendingCommand,
    long PendingAgeMs);
