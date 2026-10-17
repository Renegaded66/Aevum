using Aevum.PcTracker.Core.Models;
using Aevum.PcTracker.Core.Services;
using Xunit;

namespace Aevum.PcTracker.Core.Tests;

/// <summary>
/// PC 1.3.0 (M20): Manuell gestartete Aktivitäten („Ich will also auch dort
/// activitys starten können — entweder direkt oder mit vorlaufzeit.").
///
/// Die Regeln, die hier festgenagelt werden:
///  • Start schneidet eine laufende (Regel-)Session zum Startzeitpunkt.
///  • Solange die manuelle Session läuft, starten WEDER Regeln NOCH die
///    allgemeine Aufzeichnung (kein paralleles Aufzeichnen).
///  • Idle beendet sie NICHT; beendet wird über Stop-Knopf, Pause oder Flush.
///  • Nach dem Stop zählt die Sichtbarkeit ab jetzt (keine rückwirkende,
///    überlappende Regel-Session).
/// </summary>
public class ManualSessionTests
{
    private const string TZ = "Europe/Berlin";
    private int _idCounter;

    private SessionEngine NewEngine() => new(() => $"m{++_idCounter}");

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
        bool paused = false, bool idleEnabled = true, long idleMs = 0,
        bool generalEnabled = false, string generalActivity = "", bool audio = false) => new()
    {
        NowMs = nowMs,
        Foreground = fg,
        Rules = rules,
        Paused = paused,
        IdleEnabled = idleEnabled,
        IdleThresholdMs = 900_000,
        IdleMs = idleMs,
        TimezoneId = TZ,
        GeneralEnabled = generalEnabled,
        GeneralActivityKey = generalActivity,
        AudioActive = audio,
    };

    // ------------------------------------------------------------------ Start

    [Fact]
    public void StartManual_CreatesRunningManualSession()
    {
        var engine = NewEngine();

        var upserts = engine.StartManual("work", TZ, 1_000);

        var session = Assert.Single(upserts);
        Assert.Equal(SessionEngine.ManualProgramKey, session.ProgramKey);
        Assert.Equal("work", session.ActivityKey);
        Assert.Equal(1_000, session.StartAtMs);
        Assert.Null(session.EndAtMs);
        Assert.Same(session, engine.Active);
    }

    [Fact]
    public void StartManual_CutsRunningRuleSession_AtStartTime()
    {
        var engine = NewEngine();
        var rules = new[] { Rule("minecraft.exe") };
        engine.Tick(Tick(0, Fg("minecraft.exe"), rules)); // Session läuft seit 0

        var upserts = engine.StartManual("work", TZ, 5_000);

        Assert.Equal(2, upserts.Count);
        var ended = upserts.Single(u => u.ProgramKey == "minecraft.exe");
        var manual = upserts.Single(u => u.ProgramKey == SessionEngine.ManualProgramKey);
        Assert.Equal(5_000, ended.EndAtMs);       // Schnitt zum Startzeitpunkt
        Assert.Equal(5_000, manual.StartAtMs);
        Assert.Equal("work", manual.ActivityKey);
    }

    [Fact]
    public void StartManual_CutsGeneralSession_AtStartTime()
    {
        var engine = NewEngine();
        // Allgemeine Aufzeichnung läuft (Benutzer aktiv).
        engine.Tick(Tick(0, null, Array.Empty<TrackingRule>(),
            generalEnabled: true, generalActivity: "gaming"));
        Assert.Equal(SessionEngine.GeneralProgramKey, engine.Active!.ProgramKey);

        var upserts = engine.StartManual("work", TZ, 4_000);

        var ended = upserts.Single(u => u.ProgramKey == SessionEngine.GeneralProgramKey);
        Assert.Equal(4_000, ended.EndAtMs);
        Assert.Equal(SessionEngine.ManualProgramKey, engine.Active!.ProgramKey);
    }

    [Fact]
    public void StartManual_WhileManualRunning_SwitchesActivity()
    {
        var engine = NewEngine();
        engine.StartManual("work", TZ, 1_000);

        var upserts = engine.StartManual("gaming", TZ, 9_000);

        var ended = upserts.Single(u => u.ProgramKey == SessionEngine.ManualProgramKey && u.EndAtMs is not null);
        var started = upserts.Single(u => u.ProgramKey == SessionEngine.ManualProgramKey && u.EndAtMs is null);
        Assert.Equal(9_000, ended.EndAtMs);
        Assert.Equal("gaming", started.ActivityKey);
    }

    // ------------------------------------------------------- Blockade-Pfade

    [Fact]
    public void Tick_WhileManual_DoesNotStartRuleSession()
    {
        var engine = NewEngine();
        var rules = new[] { Rule("minecraft.exe") };
        engine.StartManual("work", TZ, 1_000);

        var upserts = engine.Tick(Tick(2_000, Fg("minecraft.exe"), rules));

        // Nur der Heartbeat der manuellen Session — kein Regel-Start.
        var session = Assert.Single(upserts);
        Assert.Equal(SessionEngine.ManualProgramKey, session.ProgramKey);
        Assert.Equal(2_000, session.LastSeenAtMs);
        Assert.Equal(SessionEngine.ManualProgramKey, engine.Active!.ProgramKey);
        Assert.Equal("work", engine.Active!.ActivityKey);
    }

    [Fact]
    public void Tick_WhileManual_DoesNotStartBackgroundRuleSession()
    {
        var engine = NewEngine();
        var rule = Rule("spotify.exe") with { Mode = RecordMode.WhileRunning };
        engine.StartManual("work", TZ, 1_000);

        var tick = Tick(2_000, null, new[] { rule }) with
        {
            RunningPrograms = new[] { "spotify.exe" },
        };
        var upserts = engine.Tick(tick);

        Assert.Single(upserts);
        Assert.Equal(SessionEngine.ManualProgramKey, upserts[0].ProgramKey);
    }

    [Fact]
    public void Tick_WhileManual_DoesNotStartGeneral()
    {
        var engine = NewEngine();
        engine.StartManual("work", TZ, 1_000);

        var upserts = engine.Tick(Tick(2_000, null, Array.Empty<TrackingRule>(),
            generalEnabled: true, generalActivity: "gaming"));

        Assert.Single(upserts);
        Assert.Equal(SessionEngine.ManualProgramKey, upserts[0].ProgramKey);
    }

    // ------------------------------------------------------------- Beenden

    [Fact]
    public void Idle_DoesNotEndManualSession()
    {
        var engine = NewEngine();
        engine.StartManual("work", TZ, 1_000);

        // Nutzer ist seit 30 Minuten inaktiv — die Ansage „Arbeit" gilt trotzdem.
        var upserts = engine.Tick(Tick(1_801_000, null, Array.Empty<TrackingRule>(), idleMs: 1_800_000));

        var session = Assert.Single(upserts);
        Assert.Null(session.EndAtMs);
        Assert.Equal(SessionEngine.ManualProgramKey, engine.Active!.ProgramKey);
    }

    [Fact]
    public void StopActive_EndsManualSessionAtNow()
    {
        var engine = NewEngine();
        engine.StartManual("work", TZ, 1_000);

        var upserts = engine.StopActive(9_000);

        var session = Assert.Single(upserts);
        Assert.Equal(9_000, session.EndAtMs);
        Assert.Null(engine.Active);
    }

    [Fact]
    public void Pause_EndsManualSession()
    {
        var engine = NewEngine();
        engine.StartManual("work", TZ, 1_000);
        engine.Tick(Tick(3_000, null, Array.Empty<TrackingRule>())); // Heartbeat: last_seen = 3s

        var upserts = engine.Pause(5_000);

        var session = Assert.Single(upserts);
        Assert.Equal(3_000, session.EndAtMs); // Ende = letzte Sicht
        Assert.Null(engine.Active);
    }

    [Fact]
    public void Flush_EndsManualSessionAtLastSeen()
    {
        var engine = NewEngine();
        engine.StartManual("work", TZ, 1_000);
        engine.Tick(Tick(7_000, null, Array.Empty<TrackingRule>()));

        var upserts = engine.Flush();

        var session = Assert.Single(upserts);
        Assert.Equal(7_000, session.EndAtMs);
        Assert.Null(engine.Active);
    }

    [Fact]
    public void AfterStop_RuleSessionRestarts_WithoutBackfill()
    {
        var engine = NewEngine();
        var rules = new[] { Rule("minecraft.exe") };
        // Minecraft ist durchgehend offen — auch während die manuelle Session läuft.
        engine.Tick(Tick(0, Fg("minecraft.exe"), rules));
        engine.StartManual("work", TZ, 1_000);
        engine.Tick(Tick(2_000, Fg("minecraft.exe"), rules)); // Heartbeat, kein Start
        engine.StopActive(6_000);

        var upserts = engine.Tick(Tick(8_000, Fg("minecraft.exe"), rules));

        // Neue Regel-Session — Start NICHT rückwirkend bei 0 (sonst Überlappung
        // mit der manuellen Session), sondern ab dem Stop-Zeitpunkt.
        var session = Assert.Single(upserts);
        Assert.Equal("minecraft.exe", session.ProgramKey);
        Assert.True(session.StartAtMs >= 6_000, $"StartAtMs war {session.StartAtMs}");
        Assert.Equal(8_000, session.LastSeenAtMs);
    }
}
