using Aevum.PcTracker.Core.Models;
using Aevum.PcTracker.Core.Services;
using Xunit;

namespace Aevum.PcTracker.Core.Tests;

/// <summary>
/// Allgemeine Aufzeichnung («General»): läuft nur ohne aktive Regel, mit der
/// gewählten Aktivität, solange der PC benutzt wird (Maus/Tastatur oder Ton);
/// endet nach 1 Minute ohne Aktivität, beim Ausschalten oder Aktivitäts-Wechsel.
/// </summary>
public class GeneralActivityTests
{
    private const string TZ = "Europe/Berlin";
    private int _idCounter;

    private SessionEngine NewEngine() => new(() => $"s{++_idCounter}");

    private static TrackingRule Rule(string key, string activity = "gaming", RecordMode mode = RecordMode.Foreground) => new()
    {
        ProgramKey = key,
        ProgramName = key,
        ActivityKey = activity,
        ThresholdSeconds = 60,
        Mode = mode,
        Enabled = true,
    };

    private static ForegroundSample Fg(string key) => new()
    {
        ProgramKey = key,
        DisplayName = key,
        Path = @"C:\apps\" + key,
    };

    private static EngineTick Tick(
        long nowMs,
        ForegroundSample? fg = null,
        TrackingRule[]? rules = null,
        long idleMs = 0,
        bool audio = false,
        bool generalEnabled = true,
        string generalActivity = "digital",
        bool paused = false,
        params string[] running) => new()
    {
        NowMs = nowMs,
        Foreground = fg,
        Rules = rules ?? Array.Empty<TrackingRule>(),
        Paused = paused,
        IdleEnabled = true,
        IdleThresholdMs = 900_000,
        IdleMs = idleMs,
        TimezoneId = TZ,
        RunningPrograms = running,
        GeneralEnabled = generalEnabled,
        GeneralActivityKey = generalActivity,
        AudioActive = audio,
    };

    [Fact]
    public void General_StartsWithoutRules_WhileUserIsActive()
    {
        var engine = NewEngine();

        var upserts = engine.Tick(Tick(1_000, fg: Fg("chrome.exe")));

        var session = Assert.Single(upserts);
        Assert.Equal(SessionEngine.GeneralProgramKey, session.ProgramKey);
        Assert.Equal(SessionEngine.GeneralProgramName, session.ProgramName);
        Assert.Equal("digital", session.ActivityKey);
        Assert.Equal(1_000, session.StartAtMs);
        Assert.Null(session.EndAtMs);
        Assert.Same(session, engine.Active);
    }

    [Fact]
    public void General_DoesNotStart_WhenDisabled()
    {
        var engine = NewEngine();

        var upserts = engine.Tick(Tick(1_000, fg: Fg("chrome.exe"), generalEnabled: false));

        Assert.Empty(upserts);
        Assert.Null(engine.Active);
    }

    [Fact]
    public void General_DoesNotStart_WithoutChosenActivity()
    {
        var engine = NewEngine();

        var upserts = engine.Tick(Tick(1_000, fg: Fg("chrome.exe"), generalActivity: ""));

        Assert.Empty(upserts);
        Assert.Null(engine.Active);
    }

    [Fact]
    public void General_DoesNotStart_WhilePaused()
    {
        var engine = NewEngine();

        var upserts = engine.Tick(Tick(1_000, fg: Fg("chrome.exe"), paused: true));

        Assert.Empty(upserts);
        Assert.Null(engine.Active);
    }

    [Fact]
    public void General_Ends_AfterOneMinuteIdle()
    {
        var engine = NewEngine();
        engine.Tick(Tick(0)); // Start (aktiv)
        engine.Tick(Tick(30_000, idleMs: 5_000)); // noch aktiv → letzter Aktiv-Moment

        var upserts = engine.Tick(Tick(92_000, idleMs: 67_000));

        var ended = Assert.Single(upserts);
        Assert.Equal(30_000, ended.EndAtMs);
        Assert.Null(engine.Active);
    }

    [Fact]
    public void General_KeepsRunning_WhileOnlyIdleIsBelowAMinute()
    {
        var engine = NewEngine();
        engine.Tick(Tick(0));

        var upserts = engine.Tick(Tick(60_000, idleMs: 59_000));

        var session = Assert.Single(upserts);
        Assert.Null(session.EndAtMs);
        Assert.Equal(60_000, session.LastSeenAtMs);
    }

    [Fact]
    public void General_AudioCountsAsActivity()
    {
        var engine = NewEngine();
        engine.Tick(Tick(0));

        // 10 Minuten ohne Eingabe — aber Ton läuft: Aufzeichnung bleibt.
        var upserts = engine.Tick(Tick(600_000, idleMs: 600_000, audio: true));
        var session = Assert.Single(upserts);
        Assert.Null(session.EndAtMs);

        // Ton endet → nach einer Minute Ruhe ist Schluss (Ende = letzter Ton-Moment).
        var ended = Assert.Single(engine.Tick(Tick(640_000, idleMs: 640_000, audio: false)));
        Assert.Equal(600_000, ended.EndAtMs);
        Assert.Null(engine.Active);
    }

    [Fact]
    public void General_Stops_WhenToggledOff()
    {
        var engine = NewEngine();
        engine.Tick(Tick(0));

        var upserts = engine.Tick(Tick(10_000, generalEnabled: false));

        var ended = Assert.Single(upserts);
        Assert.Equal(10_000, ended.EndAtMs);
        Assert.Null(engine.Active);
    }

    [Fact]
    public void General_ActivityChange_EndsAndRestarts()
    {
        var engine = NewEngine();
        engine.Tick(Tick(0, generalActivity: "work"));

        var upserts = engine.Tick(Tick(10_000, generalActivity: "leisure"));

        Assert.Equal(2, upserts.Count);
        var ended = upserts[0];
        var started = upserts[1];
        Assert.Equal("work", ended.ActivityKey);
        Assert.Equal(10_000, ended.EndAtMs);
        Assert.Equal("leisure", started.ActivityKey);
        Assert.Null(started.EndAtMs);
        Assert.Same(started, engine.Active);
    }

    [Fact]
    public void RuleWinsOverGeneral_WhenForegroundRuleAppears()
    {
        var engine = NewEngine();
        var rules = new[] { Rule("minecraft.exe", "gaming") };
        engine.Tick(Tick(0, fg: Fg("chrome.exe"), rules)); // General läuft

        var upserts = engine.Tick(Tick(10_000, fg: Fg("minecraft.exe"), rules));

        Assert.Equal(2, upserts.Count);
        var endedGeneral = upserts[0];
        var ruleSession = upserts[1];
        Assert.Equal(SessionEngine.GeneralProgramKey, endedGeneral.ProgramKey);
        Assert.Equal(10_000, endedGeneral.EndAtMs);
        Assert.Equal("minecraft.exe", ruleSession.ProgramKey);
        Assert.Equal("gaming", ruleSession.ActivityKey);
        Assert.Null(ruleSession.EndAtMs);
    }

    [Fact]
    public void General_Resumes_AfterRuleSessionEnds()
    {
        var engine = NewEngine();
        var rules = new[] { Rule("minecraft.exe", "gaming") };
        engine.Tick(Tick(0, fg: Fg("chrome.exe"), rules));
        engine.Tick(Tick(10_000, fg: Fg("minecraft.exe"), rules)); // Regel übernimmt

        // Minecraft ist zu; nach dem Threshold endet die Regel-Session und die
        // allgemeine Aufzeichnung startet im selben Tick wieder.
        var upserts = engine.Tick(Tick(80_000, fg: Fg("chrome.exe"), rules));

        Assert.Equal(2, upserts.Count);
        var endedRule = upserts[0];
        var general = upserts[1];
        Assert.Equal("minecraft.exe", endedRule.ProgramKey);
        Assert.Equal(10_000, endedRule.EndAtMs);
        Assert.Equal(SessionEngine.GeneralProgramKey, general.ProgramKey);
        Assert.Equal(80_000, general.StartAtMs);
    }

    [Fact]
    public void General_DoesNotDisplaceBackgroundRule()
    {
        var engine = NewEngine();
        var rules = new[] { Rule("spotify.exe", "music", RecordMode.WhileRunning) };

        // Spotify läuft, kein Fenster im Vordergrund → Hintergrund-Regel startet,
        // nicht die allgemeine Aufzeichnung.
        var upserts = engine.Tick(Tick(1_000, rules: rules, running: "spotify.exe"));

        var session = Assert.Single(upserts);
        Assert.Equal("spotify.exe", session.ProgramKey);
    }

    [Fact]
    public void General_Ends_WhenPaused()
    {
        var engine = NewEngine();
        engine.Tick(Tick(0));

        var upserts = engine.Tick(Tick(5_000, paused: true));

        var ended = Assert.Single(upserts);
        Assert.Equal(5_000, ended.EndAtMs);
        Assert.Null(engine.Active);
    }
}
