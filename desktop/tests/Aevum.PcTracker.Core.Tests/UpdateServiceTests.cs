using Aevum.PcTracker.Core.Services;
using Xunit;

namespace Aevum.PcTracker.Core.Tests;

/// <summary>
/// Auto-Update: Auswahl der neuesten PC-Tracker-Release aus der öffentlichen
/// GitHub-Antwort — nur stabile Versionen, nur mit Windows-ZIP, ohne Token.
/// </summary>
public class UpdateServiceTests
{
    private const string Fixture = """
    [
      {
        "tag_name": "v2.3.6",
        "name": "Aevum App 2.3.6",
        "draft": false,
        "prerelease": false,
        "body": "App-Release (kein PC-Tracker)",
        "html_url": "https://github.com/Renegaded66/Aevum/releases/tag/v2.3.6",
        "assets": [ { "name": "Aevum-app.apk", "size": 100, "browser_download_url": "https://example.com/app.apk" } ]
      },
      {
        "tag_name": "pc-tracker-1.1.0",
        "name": "PC-Tracker 1.1.0",
        "draft": false,
        "prerelease": false,
        "body": "Alte Version",
        "html_url": "https://github.com/Renegaded66/Aevum/releases/tag/pc-tracker-1.1.0",
        "assets": [ { "name": "AevumPcTracker-1.1.0-win-x64.zip", "size": 57456225, "browser_download_url": "https://example.com/1.1.0.zip" } ]
      },
      {
        "tag_name": "pc-tracker-1.2.1",
        "name": "PC-Tracker 1.2.1",
        "draft": false,
        "prerelease": false,
        "body": "Notes 1.2.1",
        "html_url": "https://github.com/Renegaded66/Aevum/releases/tag/pc-tracker-1.2.1",
        "assets": [
          { "name": "checksums.txt", "size": 10, "browser_download_url": "https://example.com/sums.txt" },
          { "name": "AevumPcTracker-1.2.1-win-x64.zip", "size": 60000000, "browser_download_url": "https://example.com/1.2.1.zip" }
        ]
      },
      {
        "tag_name": "pc-tracker-2.0.0-beta",
        "name": "Beta",
        "draft": false,
        "prerelease": true,
        "body": "Beta",
        "html_url": "https://example.com",
        "assets": [ { "name": "AevumPcTracker-2.0.0-win-x64.zip", "size": 1, "browser_download_url": "https://example.com/beta.zip" } ]
      }
    ]
    """;

    [Fact]
    public void FindsNewerTrackerRelease()
    {
        var update = UpdateService.SelectNewest(Fixture, new Version(1, 2, 0));

        Assert.NotNull(update);
        Assert.Equal(new Version(1, 2, 1), update!.Version);
        Assert.Equal("pc-tracker-1.2.1", update.Tag);
        Assert.Equal("https://example.com/1.2.1.zip", update.ZipUrl);
        Assert.Equal(60_000_000, update.ZipSizeBytes);
        Assert.Equal("Notes 1.2.1", update.Notes);
    }

    [Fact]
    public void ReturnsNull_WhenAlreadyUpToDate()
    {
        Assert.Null(UpdateService.SelectNewest(Fixture, new Version(1, 2, 1)));
        Assert.Null(UpdateService.SelectNewest(Fixture, new Version(1, 3, 0)));
    }

    [Fact]
    public void IgnoresAppReleases_Drafts_Prereleases()
    {
        const string json = """
        [
          { "tag_name": "v9.9.9", "draft": false, "prerelease": false, "assets": [ { "name": "a-win-x64.zip", "browser_download_url": "https://x/1" } ] },
          { "tag_name": "pc-tracker-9.0.0", "draft": true, "prerelease": false, "assets": [ { "name": "b-win-x64.zip", "browser_download_url": "https://x/2" } ] },
          { "tag_name": "pc-tracker-8.0.0", "draft": false, "prerelease": true, "assets": [ { "name": "c-win-x64.zip", "browser_download_url": "https://x/3" } ] }
        ]
        """;

        Assert.Null(UpdateService.SelectNewest(json, new Version(1, 0, 0)));
    }

    [Fact]
    public void IgnoresReleaseWithoutWindowsZip()
    {
        const string json = """
        [
          { "tag_name": "pc-tracker-3.0.0", "draft": false, "prerelease": false, "assets": [ { "name": "source.tar.gz", "browser_download_url": "https://x/1" } ] }
        ]
        """;

        Assert.Null(UpdateService.SelectNewest(json, new Version(1, 0, 0)));
    }

    [Fact]
    public void PicksHighestVersion_NotListOrder()
    {
        const string json = """
        [
          { "tag_name": "pc-tracker-1.10.0", "draft": false, "prerelease": false, "assets": [ { "name": "a-win-x64.zip", "browser_download_url": "https://x/new" } ] },
          { "tag_name": "pc-tracker-1.9.0", "draft": false, "prerelease": false, "assets": [ { "name": "b-win-x64.zip", "browser_download_url": "https://x/old" } ] }
        ]
        """;

        var update = UpdateService.SelectNewest(json, new Version(1, 9, 0));

        Assert.NotNull(update);
        Assert.Equal(new Version(1, 10, 0), update!.Version);
        Assert.Equal("https://x/new", update.ZipUrl);
    }

    [Fact]
    public void MalformedJson_ReturnsNull()
    {
        Assert.Null(UpdateService.SelectNewest("not json at all", new Version(1, 0, 0)));
    }
}

/// <summary>
/// PC-Tracker 1.5.1: Das Austausch-Skript (jetzt PowerShell) muss die echten
/// Fehlerfälle abdecken, die der Nutzer erlebt hat: Update lief an, aber der
/// Ordner blieb alt („nach ein paar sekunden hat sich einfach wieder 1.4.1
/// geöffnet, keine neuen dateien"); Fehler waren unsichtbar (>nul). Deshalb:
/// echtes Warten auf das Prozessende, Kopieren mit robocopy /MIR PLUS
/// Copy-Item-Fallback, Größen-Verifikation der neuen EXE und ein Log.
/// </summary>
public class UpdateSwapScriptTests
{
    private static string[] Lines()
        => UpdateInstaller.BuildSwapScriptLines(
            @"C:\Temp\AevumPcTracker-update",
            @"C:\Apps\AevumPcTracker",
            "AevumPcTracker.exe",
            4242,
            @"C:\Users\Devon\AppData\Roaming\AevumPcTracker\update.log");

    [Fact]
    public void ScriptWartetAufDasEchteProzessendeDannKopierenDannStarten()
    {
        var lines = Lines();
        // Warteschleife auf die PID des Starters (Get-Process, bis 90 s),
        // danach Notfall-Stop: genau diese PID.
        Assert.Contains(lines, l => l.Contains("while ((Get-Process -Id 4242"));
        Assert.Contains(lines, l => l.Contains("Stop-Process -Id 4242 -Force"));
        // Reihenfolge: Stop < robocopy < Start-Process der neuen EXE.
        var stopIndex = System.Array.FindIndex(lines, l => l.Contains("Stop-Process -Id 4242"));
        var copyIndex = System.Array.FindIndex(lines, l => l.Contains("robocopy"));
        var startIndex = System.Array.FindIndex(lines, l => l.Contains("Start-Process -FilePath $exe"));
        Assert.True(stopIndex >= 0 && copyIndex >= 0 && startIndex >= 0);
        Assert.True(stopIndex < copyIndex, "Stop muss vor robocopy stehen");
        Assert.True(copyIndex < startIndex, "die neue EXE darf erst nach dem Kopieren starten");
    }

    [Fact]
    public void ScriptSpiegeltMitMirUndHatCopyItemFallback()
    {
        var lines = Lines();
        var copy = Assert.Single(lines, l => l.Contains("& robocopy"));
        // /MIR = spiegeln: entfernt Dateien der alten Version, die es in der
        // neuen nicht mehr gibt („alte Version soll verschwinden“).
        Assert.Contains("/MIR", copy);
        Assert.Contains("$src", copy);
        Assert.Contains("$dst", copy);
        // robocopy-Fehler (Exitcode >= 8) und fehlende Quelle → Copy-Item-Fallback.
        Assert.Contains(lines, l => l.Contains("Copy-Item") && l.Contains("-Recurse") && l.Contains("-Force"));
        Assert.Contains(lines, l => l.Contains("$LASTEXITCODE") && l.Contains("$rc"));
    }

    [Fact]
    public void ScriptVerifiziertDieNeueExeUndLoggtAlles()
    {
        var lines = Lines();
        // Verifikation: Größe der neuen EXE aus dem Update-Ordner muss nach
        // dem Kopieren exakt im Zielordner ankommen.
        Assert.Contains(lines, l => l.Contains("$expected = (Get-Item") && l.Contains("$srcExe"));
        Assert.Contains(lines, l => l.Contains("VERIFIKATION FEHLGESCHLAGEN") || l.Contains("Verifikation"));
        // Log: jede Station mit Zeitstempel, der Log-Pfad ist eingebettet.
        Assert.Contains(lines, l => l.Contains("Write-Log") && l.Contains("Add-Content"));
        Assert.Contains(lines, l => l.Contains(@"AevumPcTracker\update.log"));
        // Nichts verschluckt die Fehler mehr (>nul-Batch ist Geschichte).
        Assert.DoesNotContain(lines, l => l.Contains(">nul"));
        Assert.DoesNotContain(lines, l => l.Contains("chcp"));
        Assert.DoesNotContain(lines, l => l.Contains("timeout /t"));
    }
}

/// <summary>
/// PC-Tracker 1.5.1: Das Update-Tagebuch — Marker schreiben/lesen/aufräumen
/// und die Bilanz beim nächsten Start (erfolgreich / fehlgeschlagen / keiner).
/// </summary>
public class UpdateJournalTests : IDisposable
{
    private readonly string _dir;

    public UpdateJournalTests()
    {
        _dir = Path.Combine(Path.GetTempPath(), "aevum-update-journal-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_dir);
    }

    public void Dispose()
    {
        try
        {
            Directory.Delete(_dir, recursive: true);
        }
        catch
        {
            // Best effort.
        }
    }

    [Fact]
    public void MarkerRundeSchreibtLiestUndRaeumtAuf()
    {
        Assert.Null(PendingUpdateStore.Read(_dir));

        PendingUpdateStore.Write(_dir, new Version(1, 5, 1), 123_456);
        var pending = PendingUpdateStore.Read(_dir);
        Assert.NotNull(pending);
        Assert.Equal(new Version(1, 5, 1), pending!.Target);
        Assert.Equal(123_456, pending.StartedAtMs);

        PendingUpdateStore.Clear(_dir);
        Assert.Null(PendingUpdateStore.Read(_dir));
    }

    [Fact]
    public void KaputteMarkerDateiWirdIgnoriert()
    {
        File.WriteAllText(PendingUpdateStore.PathFor(_dir), "kein-gueltiger-inhalt");
        Assert.Null(PendingUpdateStore.Read(_dir));
    }

    [Fact]
    public void BilanzErkenntErfolgFehlschlagUndKeinenAuftrag()
    {
        var target = new Version(1, 5, 1);
        var pending = new PendingUpdate(target, 0);

        // Läuft bereits die Zielversion (oder neuer) → erfolgreich.
        Assert.Equal(PendingUpdateOutcome.Succeeded,
            PendingUpdateStore.Classify(pending, new Version(1, 5, 1)));
        Assert.Equal(PendingUpdateOutcome.Succeeded,
            PendingUpdateStore.Classify(pending, new Version(1, 6, 0)));

        // Es läuft noch die alte Version → der Austausch kam nicht durch.
        Assert.Equal(PendingUpdateOutcome.Failed,
            PendingUpdateStore.Classify(pending, new Version(1, 5, 0)));
        Assert.Equal(PendingUpdateOutcome.Failed,
            PendingUpdateStore.Classify(pending, new Version(1, 4, 1)));

        // Kein Auftrag → nichts zu melden.
        Assert.Equal(PendingUpdateOutcome.None,
            PendingUpdateStore.Classify(null, new Version(1, 4, 1)));
    }

    [Fact]
    public void LogSchreibtMitZeitstempelUndUeberlebtFehlendeOrdnerNicht()
    {
        UpdateLog.Append(_dir, "update: Testzeile");

        var log = File.ReadAllText(UpdateLog.PathFor(_dir));
        Assert.Contains("update: Testzeile", log);
        // Zeitstempel-Präfix (yyyy-MM-dd HH:mm:ss) ist vorhanden.
        Assert.Matches(@"^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} ", log);
    }
}
