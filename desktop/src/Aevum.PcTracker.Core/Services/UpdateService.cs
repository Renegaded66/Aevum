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
    /// Baut die Zeilen des PowerShell-Austauschskripts (ausgelagert, damit die
    /// Logik testbar ist — der eigentliche Lauf bleibt Windows).
    ///
    /// Warum PowerShell statt Batch (PC-Tracker 1.5.1). Devon-Befund: „dann
    /// nach ein paar sekunden hat sich einfach wieder 1.4.1 geöffnet. Im
    /// ordner … keine neuen dateien." Das alte Batch-Skript verschluckte all
    /// seine Fehler (>nul) und war auf Windows nie echt getestet. Jetzt:
    ///  1. Warten, bis der Starter-Prozess WIRKLICH beendet ist (PID-Abfrage
    ///     in Schleife, bis 90 s; danach hart beenden). Kein festes Timeout.
    ///  2. Kopieren mit robocopy /MIR (ersetzt alte Dateien vollständig) —
    ///     und FALLBACK auf Copy-Item, wenn robocopy einen Fehler-Exitcode
    ///     (>= 8) liefert. Beides wird geloggt.
    ///  3. VERIFIKATION: Die kopierte EXE muss exakt die Größe der neuen EXE
    ///     aus dem Update-Ordner haben — erst dann gilt der Austausch als
    ///     gelungen (und nur das wird geloggt als OK).
    ///  4. Erst danach die App starten; danach aufräumen.
    /// Jede Station landet mit Zeitstempel in update.log.
    /// </summary>
    internal static string[] BuildSwapScriptLines(
        string updateDir, string appDir, string exeName, int processId, string logPath)
    {
        return new[]
        {
            "$ErrorActionPreference = 'SilentlyContinue'",
            $"$log = '{logPath}'",
            "function Write-Log([string]$m) { try { Add-Content -LiteralPath $log -Value (\"{0} {1}\" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $m) -Encoding UTF8 } catch { } }",
            "Write-Log 'swap: Skript gestartet'",
            $"$src = '{updateDir}'",
            $"$dst = '{appDir}'",
            $"$exe = Join-Path $dst '{exeName}'",
            // Phase 1: warten, bis der Starter-Prozess wirklich beendet ist.
            "$waited = 0",
            $"while ((Get-Process -Id {processId} -ErrorAction SilentlyContinue) -and $waited -lt 90) {{ Start-Sleep -Seconds 1; $waited++ }}",
            $"if (Get-Process -Id {processId} -ErrorAction SilentlyContinue) {{",
            $"  Write-Log \"swap: Starter laeuft nach $waited s noch - wird beendet\"",
            $"  Stop-Process -Id {processId} -Force -ErrorAction SilentlyContinue",
            "  Start-Sleep -Seconds 1",
            "} else { Write-Log \"swap: Starter beendet nach $waited s\" }",
            // Phase 2: Größe der neuen EXE merken, dann kopieren (mit Fallback).
            $"$srcExe = Join-Path $src '{exeName}'",
            "$expected = 0",
            "if (Test-Path -LiteralPath $srcExe) { $expected = (Get-Item -LiteralPath $srcExe).Length }",
            "Write-Log \"swap: kopiere Update-Ordner (erwartete exe-Groesse $expected)\"",
            "& robocopy $src $dst /MIR /R:3 /W:2 /NFL /NDL /NJH /NJS | Out-Null",
            "$rc = $LASTEXITCODE",
            "Write-Log \"swap: robocopy Exitcode $rc\"",
            "if ($null -eq $rc -or $rc -ge 8 -or $expected -eq 0) {",
            "  Write-Log 'swap: robocopy fehlgeschlagen - Fallback Copy-Item'",
            "  Copy-Item -Path (Join-Path $src '*') -Destination $dst -Recurse -Force",
            "}",
            // Phase 3: Verifikation — die neue EXE muss angekommen sein.
            "$got = 0",
            "if (Test-Path -LiteralPath $exe) { $got = (Get-Item -LiteralPath $exe).Length }",
            "if ($expected -gt 0 -and $got -eq $expected) {",
            "  Write-Log \"swap: Verifikation OK (exe $got Bytes)\"",
            "} else {",
            "  Write-Log \"swap: VERIFIKATION FEHLGESCHLAGEN (erwartet $expected, ist $got)\"",
            "}",
            // Phase 4: App starten (bleibt auch bei Fehlschlag benutzbar), aufräumen.
            "Start-Process -FilePath $exe",
            "Write-Log 'swap: App gestartet'",
            "Remove-Item -LiteralPath $src -Recurse -Force",
            "Remove-Item -LiteralPath $MyInvocation.MyCommand.Path -Force",
        };
    }

    /// <summary>
    /// Entpackt das ZIP und stößt das Austausch-Skript an: Es wartet auf das
    /// Ende dieses Prozesses, kopiert die neuen Dateien über das Programm-
    /// verzeichnis, VERIFIZIERT den Austausch und startet die App neu. Der
    /// Aufrufer beendet die App danach.
    ///
    /// Vorher werden der Update-Marker (<see cref="PendingUpdateStore"/>) und
    /// Log-Zeilen geschrieben (<see cref="UpdateLog"/>), damit der nächste
    /// Start sagen kann, ob der Austausch geklappt hat (1.5.1).
    ///
    /// Einstellungen bleiben dabei erhalten: robocopy/Copy-Item kopieren NUR
    /// die Dateien aus dem ZIP über das Programmverzeichnis. Alle Nutzerdaten
    /// (pctracker.sqlite mit Einstellungen, Aufzeichnungen, Kopplung) liegen
    /// außerhalb, unter %APPDATA%\AevumPcTracker.
    /// </summary>
    public static void ApplyAndRestart(
        string zipPath, string appDir, string exeName, string dataDirectory, Version targetVersion)
    {
        var temp = Path.GetTempPath();
        var updateDir = Path.Combine(temp, "AevumPcTracker-update");

        // Alte Austausch-Skripte früherer Versuche aufräumen (harmlos, falls
        // keins da ist). Das NEUE Skript räumt sich am Ende selbst weg.
        try
        {
            foreach (var stale in Directory.GetFiles(temp, "aevum-update-*.*"))
            {
                File.Delete(stale);
            }
        }
        catch
        {
            // Best-Effort.
        }

        UpdateLog.Append(dataDirectory,
            $"update: Download fertig, entpacke ZIP nach {updateDir}");
        if (Directory.Exists(updateDir))
        {
            Directory.Delete(updateDir, true);
        }
        Directory.CreateDirectory(updateDir);
        System.IO.Compression.ZipFile.ExtractToDirectory(zipPath, updateDir);
        var fileCount = Directory.GetFiles(updateDir, "*", SearchOption.AllDirectories).Length;
        UpdateLog.Append(dataDirectory, $"update: ZIP entpackt ({fileCount} Dateien)");

        // Marker für die Nachkontrolle beim nächsten Start.
        PendingUpdateStore.Write(dataDirectory, targetVersion, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());

        var script = Path.Combine(temp, $"aevum-update-{Environment.ProcessId}.ps1");
        File.WriteAllLines(
            script,
            BuildSwapScriptLines(updateDir, appDir, exeName, Environment.ProcessId, UpdateLog.PathFor(dataDirectory)),
            new UTF8Encoding(true)); // BOM: Windows PowerShell 5.1 liest sonst Umlaute/Pfade falsch
        Process.Start(new ProcessStartInfo(
            "powershell.exe",
            $"-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File \"{script}\"")
        {
            CreateNoWindow = true,
            UseShellExecute = false,
            WorkingDirectory = temp,
        });
        UpdateLog.Append(dataDirectory, $"update: Austausch-Skript gestartet ({script}); App beendet sich jetzt");
    }

    public void Dispose()
    {
        if (_ownsClient)
        {
            _http.Dispose();
        }
    }
}
