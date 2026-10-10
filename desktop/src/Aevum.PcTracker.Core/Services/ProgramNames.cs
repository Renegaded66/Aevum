using Aevum.PcTracker.Core.Models;

namespace Aevum.PcTracker.Core.Services;

/// <summary>Anzeigenamen und Schlüssel-Normalisierung für Programme.</summary>
public static class ProgramNames
{
    /// <summary>
    /// Anzeigename zu einem Programm-Key (exe, lowercase): zuerst die kuratierte
    /// Liste, sonst ein aufgeräumter Dateiname („some-tool.exe" → „Some Tool").
    /// </summary>
    public static string DisplayNameFor(string programKey)
    {
        foreach (var known in KnownPrograms.All)
        {
            if (string.Equals(known.Exe, programKey, StringComparison.OrdinalIgnoreCase))
            {
                return known.Name;
            }
        }
        var name = programKey;
        if (name.EndsWith(".exe", StringComparison.OrdinalIgnoreCase))
        {
            name = name[..^4];
        }
        name = name.Replace('-', ' ').Replace('_', ' ').Trim();
        if (name.Length == 0)
        {
            return programKey;
        }
        // Wortweise groß: „some-tool" → „Some Tool".
        var words = name.Split(' ', StringSplitOptions.RemoveEmptyEntries)
            .Select(w => char.ToUpperInvariant(w[0]) + w[1..]);
        return string.Join(' ', words);
    }

    /// <summary>Programm-Key aus einem Dateinamen (ohne Pfad), z. B. "Minecraft.exe" → "minecraft.exe".</summary>
    public static string KeyFor(string exeFileName)
        => exeFileName.Trim().ToLowerInvariant();

    /// <summary>
    /// Systemprogramme (Windows-Ordner, unser eigener Prozess) werden im Katalog
    /// ausgeblendet — getrackt werden sollen nutzbare Anwendungen.
    /// <paramref name="windowsDirectory"/> ist nur für Tests da.
    /// </summary>
    public static bool IsSystemExecutable(string path, string? windowsDirectory = null)
    {
        if (string.IsNullOrWhiteSpace(path))
        {
            return false;
        }
        try
        {
            var windir = windowsDirectory ?? Environment.GetFolderPath(Environment.SpecialFolder.Windows);
            // Separator-neutral vergleichen ('\\' auf Windows-Pfaden, '/' in Tests auf Linux).
            if (!string.IsNullOrEmpty(windir)
                && path.StartsWith(windir, StringComparison.OrdinalIgnoreCase)
                && path.Length > windir.Length
                && (path[windir.Length] == '\\' || path[windir.Length] == '/'))
            {
                return true;
            }
        }
        catch
        {
            // Auf Nicht-Windows liefert das leeren Pfad — dann nicht filtern.
        }
        return false;
    }
}
