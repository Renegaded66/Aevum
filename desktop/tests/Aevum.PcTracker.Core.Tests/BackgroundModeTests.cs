using Aevum.PcTracker.Core.Models;
using Aevum.PcTracker.Core.Services;
using Xunit;

namespace Aevum.PcTracker.Core.Tests;

/// <summary>
/// Aufnahme-Modus „läuft im Hintergrund“ (RecordMode.WhileRunning) sowie die
/// synchronisierten Aktivitäten und der Regel-Roundtrip inkl. Modus.
/// </summary>
public class SessionEngineBackgroundTests
{
    private const string TZ = "Europe/Berlin";
    private int _idCounter;

    private SessionEngine NewEngine() => new(() => $"s{++_idCounter}");

    private static TrackingRule Rule(string key, RecordMode mode, int thresholdSec = 60, string activity = "gaming") => new()
    {
        ProgramKey = key,
        ProgramName = key,
        ActivityKey = activity,
        ThresholdSeconds = thresholdSec,
        Mode = mode,
        Enabled = true,
    };

    private static ForegroundSample Fg(string key) => new()
    {
        ProgramKey = key,
        DisplayName = key,
        Path = @"C:\apps\" + key,
    };

    private static EngineTick Tick(long nowMs, ForegroundSample? fg, TrackingRule[] rules, params string[] running) => new()
    {
        NowMs = nowMs,
        Foreground = fg,
        Rules = rules,
        Paused = false,
        IdleEnabled = true,
        IdleThresholdMs = 900_000,
        IdleMs = 0,
        TimezoneId = TZ,
        RunningPrograms = running,
    };

    [Fact]
    public void BackgroundRule_StartsWhileRunningWithoutFocus()
    {
        var engine = NewEngine();
        var rules = new[] { Rule("spotify.exe", RecordMode.WhileRunning) };

        var upserts = engine.Tick(Tick(1_000, fg: null, rules, "spotify.exe"));

        var session = Assert.Single(upserts);
        Assert.Equal("spotify.exe", session.ProgramKey);
        Assert.Equal(1_000, session.StartAtMs);
        Assert.Null(session.EndAtMs);
        Assert.Same(session, engine.Active);
    }

    [Fact]
    public void ForegroundRule_DoesNotStartWhileOnlyRunning()
    {
        var engine = NewEngine();
        var rules = new[] { Rule("minecraft.exe", RecordMode.Foreground) };

        var upserts = engine.Tick(Tick(1_000, fg: null, rules, "minecraft.exe"));

        Assert.Empty(upserts);
        Assert.Null(engine.Active);
    }

    [Fact]
    public void BackgroundRule_KeepsRunningWithoutFocus()
    {
        var engine = NewEngine();
        var rules = new[] { Rule("spotify.exe", RecordMode.WhileRunning) };

        engine.Tick(Tick(0, fg: null, rules, "spotify.exe"));
        var upserts = engine.Tick(Tick(2_000, fg: null, rules, "spotify.exe"));

        var session = Assert.Single(upserts);
        Assert.Equal(2_000, session.LastSeenAtMs);
        Assert.Null(session.EndAtMs);
    }

    [Fact]
    public void BackgroundRule_EndsAfterThreshold_WhenProcessCloses()
    {
        var engine = NewEngine();
        var rules = new[] { Rule("spotify.exe", RecordMode.WhileRunning, thresholdSec: 60) };

        engine.Tick(Tick(0, fg: null, rules, "spotify.exe"));
        engine.Tick(Tick(10_000, fg: null, rules, "spotify.exe")); // letzte Sicht
        Assert.Empty(engine.Tick(Tick(20_000, fg: null, rules)));  // 10 s weg: noch offen

        var upserts = engine.Tick(Tick(70_000, fg: null, rules));  // 60 s weg: Ende
        var ended = Assert.Single(upserts);
        Assert.Equal(10_000, ended.EndAtMs);
        Assert.Null(engine.Active);
    }

    [Fact]
    public void ForegroundStart_DisplacesBackgroundSession()
    {
        var engine = NewEngine();
        var rules = new[]
        {
            Rule("spotify.exe", RecordMode.WhileRunning),
            Rule("chrome.exe", RecordMode.Foreground),
        };

        engine.Tick(Tick(0, fg: null, rules, "spotify.exe"));
        // Chrome kommt in den Vordergrund: Spotify-Session endet JETZT,
        // Chrome startet im selben Tick.
        var upserts = engine.Tick(Tick(30_000, Fg("chrome.exe"), rules, "spotify.exe"));

        Assert.Equal(2, upserts.Count);
        var spotifyEnded = upserts[0];
        Assert.Equal("spotify.exe", spotifyEnded.ProgramKey);
        Assert.Equal(30_000, spotifyEnded.EndAtMs);
        Assert.Equal("chrome.exe", engine.Active!.ProgramKey);
        Assert.Equal(30_000, engine.Active.StartAtMs);
    }

    [Fact]
    public void BackgroundResumes_AfterForeground_WithoutOverlap()
    {
        var engine = NewEngine();
        var rules = new[]
        {
            Rule("spotify.exe", RecordMode.WhileRunning),
            Rule("chrome.exe", RecordMode.Foreground),
        };

        engine.Tick(Tick(0, fg: null, rules, "spotify.exe"));
        engine.Tick(Tick(30_000, Fg("chrome.exe"), rules, "spotify.exe"));
        // Chrome ist weg, Spotify läuft weiter → neue Spotify-Session,
        // Start an das Ende von Chrome gekappt (keine große Überlappung).
        var upserts = engine.Tick(Tick(200_000, fg: null, rules, "spotify.exe"));

        Assert.Equal(2, upserts.Count);
        var chromeEnded = upserts[0];
        Assert.Equal("chrome.exe", chromeEnded.ProgramKey);
        Assert.Equal(30_000, chromeEnded.EndAtMs);
        var spotify = upserts[1];
        Assert.Equal("spotify.exe", spotify.ProgramKey);
        Assert.Equal(30_000, spotify.StartAtMs);
        Assert.Null(spotify.EndAtMs);
    }

    [Fact]
    public void NotifyRulesChanged_DoesNotBackdateRunningSession()
    {
        var engine = NewEngine();
        var rules = new[] { Rule("spotify.exe", RecordMode.WhileRunning) };

        // 30 s läuft Spotify, aber (noch) ohne Regel.
        engine.Tick(Tick(0, fg: null, Array.Empty<TrackingRule>(), "spotify.exe"));
        engine.Tick(Tick(30_000, fg: null, Array.Empty<TrackingRule>(), "spotify.exe"));
        // Regel anlegen: die Aufnahme zählt ab jetzt, nicht rückwirkend.
        engine.NotifyRulesChanged(30_000);
        var upserts = engine.Tick(Tick(30_000, fg: null, rules, "spotify.exe"));

        var session = Assert.Single(upserts);
        Assert.Equal(30_000, session.StartAtMs);
    }

    [Fact]
    public void Pause_FlushesBackgroundSession_ResumeCountsFromNow()
    {
        var engine = NewEngine();
        var rules = new[] { Rule("spotify.exe", RecordMode.WhileRunning) };

        engine.Tick(Tick(0, fg: null, rules, "spotify.exe"));
        engine.Tick(Tick(5_000, fg: null, rules, "spotify.exe")); // letzte Sicht
        var flush = engine.Pause(5_100); // Nutzer pausiert

        var ended = Assert.Single(flush);
        Assert.Equal(5_000, ended.EndAtMs);
        Assert.Null(engine.Active);

        // Fortsetzen: neue Session startet ab jetzt (kein rückwirkender Start).
        var upserts = engine.Tick(Tick(40_000, fg: null, rules, "spotify.exe"));
        var resumed = Assert.Single(upserts);
        Assert.Equal(40_000, resumed.StartAtMs);
    }
}

/// <summary>Persistenz: synchronisierte Aktivitäten + Regel-Roundtrip inkl. Modus.</summary>
public class SyncedStoreTests : IDisposable
{
    private readonly string _dir;
    private readonly string _dbPath;

    public SyncedStoreTests()
    {
        _dir = Path.Combine(Path.GetTempPath(), "aevum-synced-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_dir);
        _dbPath = Path.Combine(_dir, "t.sqlite");
    }

    public void Dispose()
    {
        try
        {
            Directory.Delete(_dir, recursive: true);
        }
        catch
        {
            // Temp-Verzeichnis — Aufräumen ist Best-Effort.
        }
    }

    [Fact]
    public void SyncedActivities_ReplaceAndRoundtrip()
    {
        using var store = new SessionStore(_dbPath);
        store.Open();

        store.ReplaceSyncedActivities(new[]
        {
            new SyncedActivity { Key = "gaming", Name = "Zocken", Icon = "🎮", CategoryKey = "leisure", UpdatedAtMs = 1 },
            new SyncedActivity { Key = "work", Name = "Arbeit", Icon = "💼", CategoryKey = "work", UpdatedAtMs = 1 },
        });

        var list = store.GetSyncedActivities();
        Assert.Equal(2, list.Count);
        Assert.Equal("Arbeit", list[0].Name); // nach Name sortiert
        Assert.Equal("Zocken", list[1].Name);
        Assert.Equal("🎮", list[1].Icon);
        Assert.Equal("leisure", list[1].CategoryKey);

        // Kompletter Abgleich: neuer Stand ersetzt den alten.
        store.ReplaceSyncedActivities(new[]
        {
            new SyncedActivity { Key = "custom-1", Name = "Neue Aktivität", UpdatedAtMs = 2 },
        });
        var replaced = store.GetSyncedActivities();
        Assert.Single(replaced);
        Assert.Equal("custom-1", replaced[0].Key);
    }

    [Fact]
    public void Rules_Roundtrip_KeepsRecordMode()
    {
        using var store = new SessionStore(_dbPath);
        store.Open();

        store.UpsertRule(new TrackingRule
        {
            ProgramKey = "spotify.exe",
            ProgramName = "Spotify",
            ActivityKey = "music",
            ThresholdSeconds = 120,
            Mode = RecordMode.WhileRunning,
            Enabled = true,
            CreatedAtMs = 10,
            UpdatedAtMs = 10,
        });

        var loaded = Assert.Single(store.GetRules());
        Assert.Equal(RecordMode.WhileRunning, loaded.Mode);
        Assert.Equal(120, loaded.ThresholdSeconds);

        // Umschalten auf Vordergrund bleibt erhalten (Upsert aktualisiert).
        store.UpsertRule(loaded with { Mode = RecordMode.Foreground, UpdatedAtMs = 20 });
        Assert.Equal(RecordMode.Foreground, Assert.Single(store.GetRules()).Mode);
    }
}
