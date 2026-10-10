using Aevum.PcTracker.Core.Models;
using Aevum.PcTracker.Core.Services;
using Xunit;

namespace Aevum.PcTracker.Core.Tests;

/// <summary>
/// Die Session-Engine: Start/Ende/Threshold/Idle/Pause — jede Regel einzeln.
/// Zeit wird explizit in die Ticks gegeben, daher sind alle Fälle deterministisch.
/// </summary>
public class SessionEngineTests
{
    private const string TZ = "Europe/Berlin";
    private int _idCounter;

    private SessionEngine NewEngine() => new(() => $"s{++_idCounter}");

    private static TrackingRule Rule(string key, int thresholdSec = 60, string activity = "gaming", bool enabled = true) => new()
    {
        ProgramKey = key,
        ProgramName = key,
        ActivityKey = activity,
        ThresholdSeconds = thresholdSec,
        Enabled = enabled,
    };

    private static ForegroundSample Fg(string key) => new()
    {
        ProgramKey = key,
        DisplayName = key,
        Path = @"C:\apps\" + key,
    };

    private static EngineTick Tick(long nowMs, ForegroundSample? fg, TrackingRule[] rules,
        bool paused = false, bool idleEnabled = true, long idleMs = 0, long idleThresholdSec = 900) => new()
    {
        NowMs = nowMs,
        Foreground = fg,
        Rules = rules,
        Paused = paused,
        IdleEnabled = idleEnabled,
        IdleThresholdMs = idleThresholdSec * 1000L,
        IdleMs = idleMs,
        TimezoneId = TZ,
    };

    [Fact]
    public void OpenProgram_StartsSession()
    {
        var engine = NewEngine();
        var rules = new[] { Rule("minecraft.exe") };

        var upserts = engine.Tick(Tick(1_000, Fg("minecraft.exe"), rules));

        var session = Assert.Single(upserts);
        Assert.Equal("s1", session.Id);
        Assert.Equal("minecraft.exe", session.ProgramKey);
        Assert.Equal(1_000, session.StartAtMs);
        Assert.Null(session.EndAtMs);
        Assert.Same(session, engine.Active);
    }

    [Fact]
    public void UnRuledProgram_DoesNotStart()
    {
        var engine = NewEngine();
        var upserts = engine.Tick(Tick(1_000, Fg("notepad.exe"), Array.Empty<TrackingRule>()));

        Assert.Empty(upserts);
        Assert.Null(engine.Active);
    }

    [Fact]
    public void RunningSession_UpdatesLastSeen()
    {
        var engine = NewEngine();
        var rules = new[] { Rule("minecraft.exe") };

        engine.Tick(Tick(0, Fg("minecraft.exe"), rules));
        var upserts = engine.Tick(Tick(2_000, Fg("minecraft.exe"), rules));

        var session = Assert.Single(upserts);
        Assert.Equal(2_000, session.LastSeenAtMs);
        Assert.Null(session.EndAtMs);
    }

    [Fact]
    public void ShortSwitch_WithinThreshold_KeepsSessionAlive()
    {
        var engine = NewEngine();
        var rules = new[] { Rule("minecraft.exe", thresholdSec: 60) };

        engine.Tick(Tick(0, Fg("minecraft.exe"), rules));
        engine.Tick(Tick(8_000, Fg("minecraft.exe"), rules));
        // Fenster weg (Alt-Tab): noch kein Ende.
        Assert.Empty(engine.Tick(Tick(10_000, null, rules)));
        // 42 s später (unter 60 s): Session lebt, Rückkehr verlängert.
        Assert.Empty(engine.Tick(Tick(50_000, null, rules)));
        var back = engine.Tick(Tick(55_000, Fg("minecraft.exe"), rules));

        var session = Assert.Single(back);
        Assert.Null(session.EndAtMs);
        Assert.Equal(55_000, session.LastSeenAtMs);
    }

    [Fact]
    public void SwitchBeyondThreshold_EndsAtLastSeen()
    {
        var engine = NewEngine();
        var rules = new[] { Rule("minecraft.exe", thresholdSec: 60) };

        engine.Tick(Tick(0, Fg("minecraft.exe"), rules));
        engine.Tick(Tick(8_000, Fg("minecraft.exe"), rules));
        engine.Tick(Tick(10_000, null, rules));

        var upserts = engine.Tick(Tick(70_000, null, rules));

        var session = Assert.Single(upserts);
        Assert.Equal(8_000, session.EndAtMs); // Ende = letzte Sicht, nicht jetzt
        Assert.Null(engine.Active);
    }

    [Fact]
    public void SwitchToOtherTrackedProgram_NoTimeLost()
    {
        var engine = NewEngine();
        var rules = new[] { Rule("minecraft.exe", thresholdSec: 60), Rule("chrome.exe", thresholdSec: 60, activity: "browsing") };

        engine.Tick(Tick(0, Fg("minecraft.exe"), rules));
        engine.Tick(Tick(10_000, Fg("minecraft.exe"), rules));
        // Wechsel zu Chrome bei 12 s: Minecraft läuft weiter bis Threshold, Chrome wartet.
        engine.Tick(Tick(12_000, Fg("chrome.exe"), rules));
        Assert.Equal("minecraft.exe", engine.Active!.ProgramKey);

        // Nach 64 s (Threshold von Minecraft) ist er vorbei — und Chrome startet
        // rückwirkend auf seiner ersten Sicht (12 s), keine Zeit geht verloren.
        var upserts = engine.Tick(Tick(74_000, Fg("chrome.exe"), rules));

        Assert.Equal(2, upserts.Count);
        var minecraft = upserts[0];
        var chrome = upserts[1];
        Assert.Equal(10_000, minecraft.EndAtMs);
        Assert.Equal("chrome.exe", chrome.ProgramKey);
        Assert.Equal(12_000, chrome.StartAtMs); // erste Sicht, nicht 74 s
        Assert.Null(chrome.EndAtMs);
    }

    [Fact]
    public void Idle_EndsSessionAtLastInput()
    {
        var engine = NewEngine();
        var rules = new[] { Rule("minecraft.exe") };

        engine.Tick(Tick(0, Fg("minecraft.exe"), rules));
        engine.Tick(Tick(500_000, Fg("minecraft.exe"), rules)); // aktiv bis 500 s
        // Bei 1400 s: seit 900 s keine Eingabe → Ende zur letzten Eingabe (500 s).
        var upserts = engine.Tick(Tick(1_400_000, Fg("minecraft.exe"), rules, idleMs: 900_000));

        var session = Assert.Single(upserts);
        Assert.Equal(500_000, session.EndAtMs);
        Assert.Null(engine.Active);
    }

    [Fact]
    public void Idle_BlocksStart_AndRestartsFromWakeup()
    {
        var engine = NewEngine();
        var rules = new[] { Rule("minecraft.exe") };

        // Nutzer ist seit 20 Minuten weg, Minecraft ist vorne → nichts startet.
        Assert.Empty(engine.Tick(Tick(1_000_000, Fg("minecraft.exe"), rules, idleMs: 1_200_000)));
        Assert.Null(engine.Active);

        // Nutzer kommt zurück: die Session startet JETZT, nicht rückwirkend.
        var upserts = engine.Tick(Tick(1_002_000, Fg("minecraft.exe"), rules, idleMs: 0));

        var session = Assert.Single(upserts);
        Assert.Equal(1_002_000, session.StartAtMs);
    }

    [Fact]
    public void IdleDisabled_NeverEndsOnItsOwn()
    {
        var engine = NewEngine();
        var rules = new[] { Rule("vlc.exe", activity: "video") };

        engine.Tick(Tick(0, Fg("vlc.exe"), rules));
        var upserts = engine.Tick(Tick(3_600_000, Fg("vlc.exe"), rules, idleEnabled: false, idleMs: 3_000_000));

        var session = Assert.Single(upserts);
        Assert.Null(session.EndAtMs); // Film läuft weiter — Idle-Prüfung aus.
    }

    [Fact]
    public void Pause_EndsSessionImmediately_AndBlocksNewOnes()
    {
        var engine = NewEngine();
        var rules = new[] { Rule("minecraft.exe") };

        engine.Tick(Tick(0, Fg("minecraft.exe"), rules));
        engine.Tick(Tick(10_000, Fg("minecraft.exe"), rules));

        var paused = engine.Pause(12_000);
        var ended = Assert.Single(paused);
        Assert.Equal(10_000, ended.EndAtMs); // letzte Sicht
        Assert.Null(engine.Active);

        Assert.Empty(engine.Tick(Tick(14_000, Fg("minecraft.exe"), rules, paused: true)));
        Assert.Null(engine.Active);

        // Fortsetzen: zählt ab jetzt.
        var resumed = engine.Tick(Tick(20_000, Fg("minecraft.exe"), rules, paused: false));
        var session = Assert.Single(resumed);
        Assert.Equal(20_000, session.StartAtMs);
    }

    [Fact]
    public void RuleDisabled_WhileRunning_EndsAtLastSeen()
    {
        var engine = NewEngine();
        var enabled = new[] { Rule("minecraft.exe") };
        var disabled = new[] { Rule("minecraft.exe", enabled: false) };

        engine.Tick(Tick(0, Fg("minecraft.exe"), enabled));
        engine.Tick(Tick(6_000, Fg("minecraft.exe"), enabled));

        var upserts = engine.Tick(Tick(8_000, Fg("minecraft.exe"), disabled));
        var session = Assert.Single(upserts);
        Assert.Equal(6_000, session.EndAtMs);
        Assert.Null(engine.Active);
    }

    [Fact]
    public void NotifyRulesChanged_StartsCountingNow()
    {
        var engine = NewEngine();

        // Programm ist seit 600 s vorne, ohne Regel.
        engine.Tick(Tick(0, Fg("minecraft.exe"), Array.Empty<TrackingRule>()));
        engine.Tick(Tick(600_000, Fg("minecraft.exe"), Array.Empty<TrackingRule>()));

        // Nutzer legt die Regel an → Zählung ab jetzt, keine Rückwirkung.
        var rules = new[] { Rule("minecraft.exe") };
        engine.NotifyRulesChanged(600_000);
        var upserts = engine.Tick(Tick(602_000, Fg("minecraft.exe"), rules));

        var session = Assert.Single(upserts);
        Assert.Equal(600_000, session.StartAtMs);
    }

    [Fact]
    public void NoForeground_NoStart_AndNoChange()
    {
        var engine = NewEngine();
        var rules = new[] { Rule("minecraft.exe") };

        Assert.Empty(engine.Tick(Tick(0, null, rules)));
        Assert.Null(engine.Active);
    }

    [Fact]
    public void ThresholdIsPerRule()
    {
        var engine = NewEngine();
        var rules = new[] { Rule("minecraft.exe", thresholdSec: 300) };

        engine.Tick(Tick(0, Fg("minecraft.exe"), rules));
        engine.Tick(Tick(1_000, Fg("minecraft.exe"), rules));

        // Nach 100 s Pause (unter 300 s Threshold) läuft die Session noch.
        Assert.Empty(engine.Tick(Tick(101_000, null, rules)));
        Assert.NotNull(engine.Active);

        // Nach 301 s ist sie vorbei.
        var upserts = engine.Tick(Tick(302_000, null, rules));
        var session = Assert.Single(upserts);
        Assert.Equal(1_000, session.EndAtMs);
    }
}
