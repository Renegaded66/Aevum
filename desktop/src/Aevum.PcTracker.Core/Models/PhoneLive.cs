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

/// <summary>
/// PC-Tracker 1.6.0: Das Verifikations-Gate. Devon: „Am besten sollte die
/// desktop app erst erlauben, überhaupt irgendwas aufzuzeichnen […] wenn die
/// desktop app verifiziert hat, das auf dem handy nichts läuft, ansonsten
/// kommt eine meldung das der stand des handys nicht verifiziert werden
/// konnte. Damit das problem nicht weiterhin besteht."
/// Der PC startet nur noch, wenn FRISCH bestätigt ist, dass am Handy nichts
/// läuft — keine Bestätigung heißt: nichts starten + Meldung im Dashboard.
/// </summary>
public enum PhoneGateVerdict
{
    /// <summary>
    /// Noch nie ein gekoppeltes Handy aufgetreten (PhoneKnownAtMs == 0) →
    /// das Gate ist aus, der PC zeichnet wie immer auf. Erst ab dem ersten
    /// echten Handy-Kontakt greift die Verifikationspflicht.
    /// </summary>
    NotCoupled = 0,
    /// <summary>Frische Handy-Aufzeichnung (≤ PriorityWindowMs) → PC hält sich zurück.</summary>
    Recording = 1,
    /// <summary>Frisch bestätigt: am Handy läuft nichts → der PC darf starten.</summary>
    VerifiedIdle = 2,
    /// <summary>
    /// Keine frische Bestätigung (Sendelücke, nur veraltetes „zeichnet auf",
    /// nie gemeldet) → der PC startet NICHTS Neues und meldet es ehrlich.
    /// </summary>
    Unverified = 3,
}

/// <summary>Momentaufnahme fürs Dashboard (reine Werte, UI-tauglich).</summary>
public sealed record PhoneLiveStatus(
    PhoneLiveSession? Session,
    bool Stale,
    PhoneLiveCommand? PendingCommand,
    long PendingAgeMs,
    /// <summary>
    /// PC 1.6.0: Alter der letzten „nichts läuft"-Bestätigung in ms
    /// (-1 = noch nie) — Grundlage der Verifikations-Anzeige.
    /// </summary>
    long IdleVerifiedAgeMs = -1);
