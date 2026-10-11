namespace Aevum.PcTracker.Core.Models;

/// <summary>Wie eine Regel aufzeichnet (siehe Regeln-Tab, pro Regel wählbar).</summary>
public enum RecordMode
{
    /// <summary>Nur solange das Programm das Vordergrundfenster ist (klassisch).</summary>
    Foreground = 0,

    /// <summary>Auch, wenn das Programm nur im Hintergrund läuft (z. B. Musik, Spiele-Launcher).</summary>
    WhileRunning = 1,
}

/// <summary>
/// Zugeordnetes Programm: die .exe (lowercase) ist der stabile Key,
/// <see cref="ProgramName"/> der Anzeigename für die UI.
/// </summary>
public sealed record TrackingRule
{
    public required string ProgramKey { get; init; }
    public required string ProgramName { get; init; }
    /// <summary>Key aus <see cref="ActivityCatalog"/> oder einer vom Handy synchronisierten Aktivität.</summary>
    public required string ActivityKey { get; init; }
    /// <summary>Aufnahme endet, wenn das Programm so lange nicht mehr sichtbar war.</summary>
    public int ThresholdSeconds { get; init; } = 60;
    /// <summary>Vordergrund oder auch im Hintergrund laufend (Standard: Vordergrund).</summary>
    public RecordMode Mode { get; init; } = RecordMode.Foreground;
    public bool Enabled { get; init; } = true;
    public long CreatedAtMs { get; init; }
    public long UpdatedAtMs { get; init; }
}

/// <summary>
/// Eine aufgezeichnete PC-Session. <see cref="EndAtMs"/> ist null, solange die
/// Session läuft (Threshold-Fenster offen) — abgeschlossene Sessions haben immer
/// ein Ende und werden nur die an Aevum ausgeliefert.
/// </summary>
public sealed record TrackedSession
{
    public required string Id { get; init; }
    public required string ProgramKey { get; init; }
    public required string ProgramName { get; init; }
    public required string ActivityKey { get; init; }
    public required long StartAtMs { get; init; }
    public required long LastSeenAtMs { get; init; }
    public long? EndAtMs { get; init; }
    public required string TimezoneId { get; init; }
    public required long CreatedAtMs { get; init; }

    public bool IsOpen => EndAtMs is null;
    public long DurationMs => (EndAtMs ?? LastSeenAtMs) - StartAtMs;
}

/// <summary>Ein am PC gesehenes Programm (Grundlage der Auswahlliste im Regeln-Tab).</summary>
public sealed record ProgramEntry
{
    public required string ProgramKey { get; init; }
    public required string Name { get; init; }
    public string Path { get; init; } = "";
    public long FirstSeenAtMs { get; init; }
    public long LastSeenAtMs { get; init; }
    /// <summary>Systemprogramme (Windows-Ordner) werden ausgeblendet statt gelöscht.</summary>
    public bool Hidden { get; init; }
}

/// <summary>Momentaufnahme des Vordergrundfensters (oder null = kein getrackbares Fenster).</summary>
public sealed record ForegroundSample
{
    public required string ProgramKey { get; init; }
    public required string DisplayName { get; init; }
    public string Path { get; init; } = "";
    public string WindowTitle { get; init; } = "";
}

/// <summary>Ein gerade laufendes Programm mit sichtbarem Fenster („Jetzt offen“-Liste).</summary>
public sealed record RunningProgram
{
    public required string ProgramKey { get; init; }
    public required string Name { get; init; }
    public string Title { get; init; } = "";
}

/// <summary>
/// Eine vom Handy synchronisierte Aktivität (aus Aevum) — Grundlage für das
/// Aktivitäts-Dropdown im PC-Regeln-Tab. Schlüssel ist die ActivityType-ID der
/// App; so ordnen beide Seiten identisch zu (siehe PC-TRACKER-PROTOCOL.md, 4b).
/// </summary>
public sealed record SyncedActivity
{
    public required string Key { get; init; }
    public required string Name { get; init; }
    public string Icon { get; init; } = "";
    public string CategoryKey { get; init; } = "";
    public long UpdatedAtMs { get; init; }
}
