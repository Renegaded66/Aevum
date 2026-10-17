using System.Diagnostics;
using System.Net.Http;
using System.Text;
using System.Text.Json;

namespace Aevum.PcTracker.Core.Services;

/// <summary>Ein verfügbares Update aus den öffentlichen GitHub-Releases.</summary>
public sealed record AvailableUpdate(
    Version Version,
    string Tag,
    string Notes,
    string ZipUrl,
    string ZipName,
    long ZipSizeBytes,
    string HtmlUrl);

/// <summary>Ergebnis einer Update-Prüfung — Fehler (offline/Rate-Limit) wird von „kein Update“ unterschieden.</summary>
public sealed record UpdateCheckResult(AvailableUpdate? Update, bool Failed);

/// <summary>
/// Prüft die öffentlichen GitHub-Releases des Projekts auf eine neuere
/// PC-Tracker-Version. Bewusst OHNE Token/Anmeldung — nur der öffentliche
/// API-Endpunkt (die anonyme Rate reicht für einen Check pro Programmstart).
/// Jeder Fehler (offline, Rate-Limit, API-Änderung) führt still zu „kein Update“.
/// </summary>
public sealed class UpdateService : IDisposable
{
    public const string ReleasesUrl =
        "https://api.github.com/repos/Renegaded66/Aevum/releases?per_page=30";

    /// <summary>Nur Releases dieses Produkts zählen (die App selbst hat andere Tags).</summary>
    public const string TagPrefix = "pc-tracker-";

    private readonly HttpClient _http;
    private readonly bool _ownsClient;

    public UpdateService(HttpClient? http = null)
    {
        _ownsClient = http is null;
        _http = http ?? new HttpClient { Timeout = TimeSpan.FromSeconds(15) };
        if (_ownsClient)
        {
            _http.DefaultRequestHeaders.UserAgent.ParseAdd("AevumPcTracker");
            _http.DefaultRequestHeaders.Accept.ParseAdd("application/vnd.github+json");
        }
    }

    /// <summary>Ergebnis einer Update-Prüfung: Update (oder null) + Fehler-Flag (offline/Rate-Limit).</summary>
    public async Task<UpdateCheckResult> CheckAsync(Version current, CancellationToken ct = default)
    {
        try
        {
            var json = await _http.GetStringAsync(ReleasesUrl, ct).ConfigureAwait(false);
            return new UpdateCheckResult(SelectNewest(json, current), false);
        }
        catch
        {
            return new UpdateCheckResult(null, true); // offline / Rate-Limit → still weiterlaufen
        }
    }

    /// <summary>Reine Auswahl-Logik (testbar): neueste stabile PC-Tracker-Release mit Windows-ZIP.</summary>
    public static AvailableUpdate? SelectNewest(string releasesJson, Version current)
    {
        JsonDocument doc;
        try
        {
            doc = JsonDocument.Parse(releasesJson);
        }
        catch (JsonException)
        {
            return null; // unerwartete Antwort (kein JSON-Array) → „kein Update“
        }
        using (doc)
        {
            return SelectNewest(doc, current);
        }
    }

    private static AvailableUpdate? SelectNewest(JsonDocument doc, Version current)
    {
        if (doc.RootElement.ValueKind != JsonValueKind.Array)
        {
            return null;
        }
        AvailableUpdate? best = null;
        foreach (var rel in doc.RootElement.EnumerateArray())
        {
            if (rel.TryGetProperty("draft", out var draft) && draft.GetBoolean())
            {
                continue;
            }
            if (rel.TryGetProperty("prerelease", out var pre) && pre.GetBoolean())
            {
                continue;
            }
            var tag = rel.TryGetProperty("tag_name", out var tagEl) ? tagEl.GetString() ?? "" : "";
            if (!tag.StartsWith(TagPrefix, StringComparison.OrdinalIgnoreCase)
                || !Version.TryParse(tag[TagPrefix.Length..], out var version)
                || version <= current)
            {
                continue;
            }

            // Windows-ZIP-Asset (Name enthält „win-x64“, Endung .zip).
            string? zipUrl = null;
            var zipName = "";
            long size = 0;
            if (rel.TryGetProperty("assets", out var assets) && assets.ValueKind == JsonValueKind.Array)
            {
                foreach (var a in assets.EnumerateArray())
                {
                    var name = a.TryGetProperty("name", out var n) ? n.GetString() ?? "" : "";
                    if (name.EndsWith(".zip", StringComparison.OrdinalIgnoreCase)
                        && name.Contains("win-x64", StringComparison.OrdinalIgnoreCase))
                    {
                        zipUrl = a.TryGetProperty("browser_download_url", out var u) ? u.GetString() : null;
                        zipName = name;
                        size = a.TryGetProperty("size", out var s) ? s.GetInt64() : 0;
                        break;
                    }
                }
            }
            if (zipUrl is null)
            {
                continue;
            }

            if (best is null || version > best.Version)
            {
                best = new AvailableUpdate(
                    version,
                    tag,
                    rel.TryGetProperty("body", out var b) ? b.GetString() ?? "" : "",
                    zipUrl,
                    zipName,
                    size,
                    rel.TryGetProperty("html_url", out var h) ? h.GetString() ?? "" : "");
            }
        }
        return best;
    }

    public void Dispose()
    {
        if (_ownsClient)
        {
            _http.Dispose();
        }
    }
}

/// <summary>
/// Lädt das Update-ZIP und tauscht die Programmdateien nach dem Beenden der
/// App aus (kleines Batch-Skript wartet auf das Prozessende, kopiert, startet
/// neu). Ohne Installer/Admin-Rechte — funktioniert für die entpackte ZIP-App.
/// </summary>
public sealed class UpdateInstaller : IDisposable
{
    private readonly HttpClient _http;
    private readonly bool _ownsClient;

    public UpdateInstaller(HttpClient? http = null)
    {
        _ownsClient = http is null;
        _http = http ?? new HttpClient { Timeout = TimeSpan.FromMinutes(10) };
        if (_ownsClient)
        {
            _http.DefaultRequestHeaders.UserAgent.ParseAdd("AevumPcTracker");
        }
    }

    /// <summary>Kann die App in ihr eigenes Verzeichnis schreiben? Sonst nur manueller Download.</summary>
    public static bool CanWriteAppDirectory(string appDir)
    {
        try
        {
            var probe = Path.Combine(appDir, $".update-probe-{Guid.NewGuid():N}.tmp");
            File.WriteAllText(probe, "ok");
            File.Delete(probe);
            return true;
        }
        catch
        {
            return false;
        }
    }

    /// <summary>Lädt das ZIP mit Fortschritt (0–1) in den Zielpfad; gibt den Pfad zurück.</summary>
    public async Task<string> DownloadAsync(
        AvailableUpdate update, string targetPath, IProgress<double>? progress, CancellationToken ct)
    {
        var dir = Path.GetDirectoryName(targetPath);
        if (!string.IsNullOrEmpty(dir))
        {
            Directory.CreateDirectory(dir);
        }
        using var resp = await _http
            .GetAsync(update.ZipUrl, HttpCompletionOption.ResponseHeadersRead, ct)
            .ConfigureAwait(false);
        resp.EnsureSuccessStatusCode();
        var total = resp.Content.Headers.ContentLength ?? update.ZipSizeBytes;
        await using (var src = await resp.Content.ReadAsStreamAsync(ct).ConfigureAwait(false))
        await using (var dst = File.Create(targetPath))
        {
            var buffer = new byte[81_920];
            long done = 0;
            int read;
            while ((read = await src.ReadAsync(buffer, ct).ConfigureAwait(false)) > 0)
            {
                await dst.WriteAsync(buffer.AsMemory(0, read), ct).ConfigureAwait(false);
                done += read;
                if (total > 0)
                {
                    progress?.Report(Math.Clamp(done / (double)total, 0, 1));
                }
            }
        }
        return targetPath;
    }

    /// <summary>
    /// Baut die Zeilen des Austausch-Skripts (ausgelagert, damit die Logik
    /// testbar ist — der eigentliche cmd-Lauf bleibt Windows).
    ///
    /// Ablauf (PC-Tracker 1.4.1, Devon: „das fenster hat sich geschlossen,
    /// dann kam das pop up ‚the app is already active in the system tray‘ […]
    /// der ordner ist immer noch voll mit 1.3.0"):
    /// 1. Auf das ENDE des Update-Starters (PID) warten — vorher war es ein
    ///    festes „timeout /t 2": Wenn die App noch im Tray lief (Shutdown
    ///    gecancelt, alter Bug), scheiterte robocopy an gelockten Dateien,
    ///    nichts wurde ersetzt, und die danach gestartete alte EXE zeigte den
    ///    „läuft schon"-Dialog. Jetzt: warten, bis der Prozess wirklich weg
    ///    ist; nach ~45 s Notfall-`taskkill /F` genau dieser PID.
    /// 2. robocopy mit /MIR: spiegelt den neuen Stand und LÖSCHT Dateien, die
    ///    es in der neuen Version nicht mehr gibt — die alte Version
    ///    verschwindet vollständig (Devon: „Alte versionen sollten auch
    ///    überschrieben werden").
    /// 3. Erst danach die neue EXE starten.
    /// </summary>
    internal static string[] BuildSwapScriptLines(
        string updateDir, string appDir, string exeName, int processId)
    {
        return new[]
        {
            "@echo off",
            "chcp 65001 >nul",
            // Phase 1: warten, bis der Update-Starter beendet ist (max. ~45 s).
            "set WAIT=0",
            ":waitloop",
            $"tasklist /FI \"PID eq {processId}\" 2>nul | find \"{processId}\" >nul",
            "if errorlevel 1 goto copy",
            "set /a WAIT+=1",
            "if %WAIT% GEQ 45 goto kill",
            "timeout /t 1 /nobreak >nul",
            "goto waitloop",
            ":kill",
            // Notfall: Prozess hängt (Tray/Thread) → genau diese PID beenden.
            $"taskkill /PID {processId} /F >nul 2>&1",
            "timeout /t 1 /nobreak >nul",
            ":copy",
            // Phase 2: Programmdateien spiegeln — alte Dateien werden entfernt.
            $"robocopy \"{updateDir}\" \"{appDir}\" /MIR /R:3 /W:2 /NFL /NDL /NJH /NJS >nul",
            // Phase 3: neue Version starten, Aufräumen, Skript selbst löschen.
            $"start \"\" \"{Path.Combine(appDir, exeName)}\"",
            $"rmdir /s /q \"{updateDir}\"",
            "(goto) 2>nul & del \"%~f0\"",
        };
    }

    /// <summary>
    /// Entpackt das ZIP und stößt das Austausch-Skript an: Es wartet auf das
    /// Ende dieses Prozesses, kopiert die neuen Dateien über das Programm-
    /// verzeichnis und startet die App neu. Der Aufrufer beendet die App danach.
    ///
    /// Einstellungen bleiben dabei erhalten (Devon: „denke beim installieren
    /// eines updates der desktop app daran, dass die einstellungen übernommen
    /// werden sollen"): robocopy kopiert NUR die Dateien aus dem ZIP über das
    /// Programmverzeichnis. Alle Nutzerdaten (pctracker.sqlite mit
    /// Einstellungen, Aufzeichnungen, Kopplung) liegen ohnehin außerhalb,
    /// unter %APPDATA%\AevumPcTracker (AppHost.DefaultDataDirectory) — der
    /// /MIR-Spiegel betrifft ausschließlich den Programmordner.
    /// </summary>
    public static void ApplyAndRestart(string zipPath, string appDir, string exeName)
    {
        var updateDir = Path.Combine(Path.GetTempPath(), "AevumPcTracker-update");
        if (Directory.Exists(updateDir))
        {
            Directory.Delete(updateDir, true);
        }
        Directory.CreateDirectory(updateDir);
        System.IO.Compression.ZipFile.ExtractToDirectory(zipPath, updateDir);

        var script = Path.Combine(Path.GetTempPath(), $"aevum-update-{Environment.ProcessId}.cmd");
        File.WriteAllLines(
            script,
            BuildSwapScriptLines(updateDir, appDir, exeName, Environment.ProcessId),
            new UTF8Encoding(false));
        Process.Start(new ProcessStartInfo("cmd.exe", $"/c \"{script}\"")
        {
            CreateNoWindow = true,
            UseShellExecute = false,
            WorkingDirectory = Path.GetTempPath(),
        });
    }

    public void Dispose()
    {
        if (_ownsClient)
        {
            _http.Dispose();
        }
    }
}
