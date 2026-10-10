namespace Aevum.PcTracker.Core.Models;

/// <summary>
/// Laufzeit-Einstellungen der App. Werden als Key/Value in SQLite abgelegt
/// (siehe <see cref="Services.SettingsService"/>); Defaults hier = frische Installation.
/// </summary>
public sealed class AppSettings
{
    // --- Gerät ---
    public string DeviceId { get; set; } = "";
    public string DeviceName { get; set; } = "";

    // --- Server ---
    public int ApiPort { get; set; } = 47825;
    public int DiscoveryPort { get; set; } = 47826;

    // --- Aufzeichnung ---
    /// <summary>Globaler Pausen-Schalter (Dashboard/Tray).</summary>
    public bool TrackingPaused { get; set; }
    /// <summary>Inaktivitäts-Erkennung: keine Eingabe X Sekunden → laufende Aufnahme endet.</summary>
    public bool IdleEnabled { get; set; } = true;
    public int IdleThresholdSeconds { get; set; } = 900; // 15 Minuten
    /// <summary>Standard-Threshold für neue Regeln (Aufnahme-Ende nach Programm-Schluss).</summary>
    public int DefaultThresholdSeconds { get; set; } = 60;
    /// <summary>Aufbewahrung der lokalen Session-Historie.</summary>
    public int RetentionDays { get; set; } = 365;

    // --- Verhalten ---
    public bool Autostart { get; set; }
    public bool StartMinimized { get; set; }
    /// <summary>„system", „light" oder „dark".</summary>
    public string Theme { get; set; } = "system";

    // --- Intern ---
    /// <summary>Token für die Kopplung (32 Byte hex). Nie anzeigen, nie loggen.</summary>
    public string ApiToken { get; set; } = "";
    public long CreatedAtMs { get; set; }
}

/// <summary>Erlaubte Threshold-Stufen für die UI (Sekunden).</summary>
public static class ThresholdOptions
{
    public static readonly int[] Seconds = { 30, 60, 120, 180, 300, 600, 900, 1800 };

    public static string Label(int seconds) => seconds switch
    {
        < 60 => $"{seconds} Sekunden",
        60 => "1 Minute",
        120 => "2 Minuten",
        180 => "3 Minuten",
        300 => "5 Minuten",
        600 => "10 Minuten",
        900 => "15 Minuten",
        1800 => "30 Minuten",
        _ => $"{seconds / 60} Minuten",
    };
}
