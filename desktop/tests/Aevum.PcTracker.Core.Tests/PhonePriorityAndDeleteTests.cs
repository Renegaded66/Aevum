using Aevum.PcTracker.Core.Models;
using Aevum.PcTracker.Core.Services;
using Xunit;

namespace Aevum.PcTracker.Core.Tests;

/// <summary>
/// PC-Tracker 1.5.0 — HANDY-VORRANG. Devon: „Offensichtlich sollte die pc
/// aufzeichnung gestoppt werden, wenn etwas am handy beginnt aufzuzeichnen […]
/// egal ob es nur die allgemeine pc aktivität […] oder ein bestimmtes
/// programm […] Also bedenke dass immer nur eine sache gleichzeitig
/// aufgezeichnet werden können soll und handy hat vorrang."
///
/// Diese Testklasse nagelt fest:
///  • Handy-Aufzeichnung beendet JEDE laufende PC-Session (Regel, allgemein,
///    manuell) mit Ende = jetzt.
///  • Solange das Handy aufzeichnet, startet nichts Neues.
///  • Nach dem Handy-Ende zählt die Sichtbarkeit FRISCH ab jetzt (keine
///    rückwirkende, überlappende Session — sonst würde die PC-Session die
///    ganze Handy-Zeit doppelt beanspruchen).
/// </summary>
public class PhonePriorityTests
{
    private const string TZ = "Europe/Berlin";
    private int _idCounter;

    private SessionEngine NewEngine() => new(() => $"p{++_idCounter}");

    private static TrackingRule Rule(string key, int thresholdSec = 60, string activity = "gaming") => new()
    {
        ProgramKey = key,
        ProgramName = key,
        ActivityKey = activity,
        ThresholdSeconds = thresholdSec,
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
        ForegroundSample? fg,
        TrackingRule[] rules,
        bool phoneRecording,
        bool generalEnabled = false,
        string generalActivity = "") => new()
    {
        NowMs = nowMs,
        Foreground = fg,
        Rules = rules,
        Paused = false,
        IdleEnabled = true,
        IdleThresholdMs = 900_000,
        IdleMs = 0,
        TimezoneId = TZ,
        GeneralEnabled = generalEnabled,
        GeneralActivityKey = generalActivity,
        AudioActive = false,
        PhoneRecording = phoneRecording,
    };

    [Fact]
    public void HandyBeendetDieLaufendeRegelSession_Sofort()
    {
        var engine = NewEngine();
        var rules = new[] { Rule("minecraft.exe") };
        engine.Tick(Tick(1_000, Fg("minecraft.exe"), rules, phoneRecording: false));
        Assert.NotNull(engine.Active);

        var upserts = engine.Tick(Tick(5_000, Fg("minecraft.exe"), rules, phoneRecording: true));

        var ended = Assert.Single(upserts);
        Assert.Equal(1_000, ended.StartAtMs);
        Assert.Equal(5_000, ended.EndAtMs); // Ende = jetzt, nicht „letzte Sicht"
        Assert.Null(engine.Active);
    }

    [Fact]
    public void HandyBeendetDieAllgemeineAufzeichnung()
    {
        var engine = NewEngine();
        engine.Tick(Tick(1_000, null, Array.Empty<TrackingRule>(),
            phoneRecording: false, generalEnabled: true, generalActivity: "digital"));
        Assert.NotNull(engine.Active);

        var upserts = engine.Tick(Tick(9_000, null, Array.Empty<TrackingRule>(),
            phoneRecording: true, generalEnabled: true, generalActivity: "digital"));

        var ended = Assert.Single(upserts);
        Assert.Equal(SessionEngine.GeneralProgramKey, ended.ProgramKey);
        Assert.Equal(9_000, ended.EndAtMs);
        Assert.Null(engine.Active);
    }

    [Fact]
    public void HandyBeendetAuchEineManuelleSession()
    {
        var engine = NewEngine();
        engine.StartManual("work", TZ, 1_000);

        var upserts = engine.Tick(Tick(4_000, null, Array.Empty<TrackingRule>(), phoneRecording: true));

        var ended = Assert.Single(upserts);
        Assert.Equal(SessionEngine.ManualProgramKey, ended.ProgramKey);
        Assert.Equal(4_000, ended.EndAtMs);
        Assert.Null(engine.Active);
    }

    /// <summary>
    /// PC 1.5.2: Der Grenzfall „PC startete Sekunden vor einer Handy-Meldung"
    /// (typisch nach Funkstille) darf KEINEN Mini-Eintrag hinterlassen —
    /// unter 2 s wird die vom Vorrang beendete Session verworfen.
    /// </summary>
    [Fact]
    public void MiniArtefaktDurchVorrangWirdNichtGespeichert()
    {
        var engine = NewEngine();
        engine.StartManual("work", TZ, 1_000);

        // Session ist beim Vorrang-Tick erst 1,5 s alt.
        var upserts = engine.Tick(Tick(2_500, null, Array.Empty<TrackingRule>(), phoneRecording: true));

        Assert.Empty(upserts);      // kein Eintrag im Verlauf / Handy-Sync
        Assert.Null(engine.Active); // beendet wird sie trotzdem
    }

    [Fact]
    public void WaehrendHandyAufzeichnungStartetNichtsNeues()
    {
        var engine = NewEngine();
        var rules = new[] { Rule("minecraft.exe", thresholdSec: 1) };

        // Programm sichtbar UND Regel aktiv — trotzdem: nichts startet.
        var upserts = engine.Tick(Tick(1_000, Fg("minecraft.exe"), rules, phoneRecording: true));

        Assert.Empty(upserts);
        Assert.Null(engine.Active);
    }

    [Fact]
    public void NachDemHandyEndeZaehltDieSichtbarkeitAbJetzt_NichtRueckwirkend()
    {
        var engine = NewEngine();
        var rules = new[] { Rule("minecraft.exe", thresholdSec: 1) };

        // Programm ist durchgehend im Vordergrund, das Handy zeichnet von
        // 1_000 bis 5_000 auf — danach endet die Handy-Aufzeichnung.
        engine.Tick(Tick(1_000, Fg("minecraft.exe"), rules, phoneRecording: true));
        engine.Tick(Tick(3_000, Fg("minecraft.exe"), rules, phoneRecording: true));
        engine.Tick(Tick(5_000, Fg("minecraft.exe"), rules, phoneRecording: true));

        var upserts = engine.Tick(Tick(7_000, Fg("minecraft.exe"), rules, phoneRecording: false));

        var started = Assert.Single(upserts);
        Assert.Equal("minecraft.exe", started.ProgramKey);
        // NICHT 1_000 (erste Sicht) — die PC-Session beginnt erst nach dem
        // Handy (letzter Handy-Tick), sonst läge sie über der Handy-Zeit.
        Assert.Equal(5_000, started.StartAtMs);
    }
}

/// <summary>
/// PC-Tracker 1.5.0 — Verlauf-Löschung. Devon: „Ich will auch in der desktop
/// app aufzeichnungen aus dem verlauf entfernen können."
/// </summary>
public class HistoryDeleteTests : IDisposable
{
    private readonly string _dir;
    private readonly string _dbPath;

    public HistoryDeleteTests()
    {
        _dir = Path.Combine(Path.GetTempPath(), "aevum-pctracker-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_dir);
        _dbPath = Path.Combine(_dir, "test.sqlite");
    }

    public void Dispose()
    {
        try
        {
            Directory.Delete(_dir, recursive: true);
        }
        catch
        {
            // Aufräumen ist best effort.
        }
    }

    private SessionStore NewStore()
    {
        var store = new SessionStore(_dbPath);
        store.Open();
        return store;
    }

    private static TrackedSession Session(string id, long start, long? end, long lastSeen) => new()
    {
        Id = id,
        ProgramKey = "minecraft.exe",
        ProgramName = "Minecraft",
        ActivityKey = "gaming",
        StartAtMs = start,
        EndAtMs = end,
        LastSeenAtMs = lastSeen,
        TimezoneId = "Europe/Berlin",
        CreatedAtMs = start,
    };

    [Fact]
    public void AbgeschlosseneAufzeichnungIstLoeschbar()
    {
        using var store = NewStore();
        store.Apply(new[] { Session("a", 1_000, 5_000, 5_000) });

        Assert.Equal(1, store.DeleteSessionById("a"));
        Assert.Empty(store.QueryCompletedSessions(0, 100));
    }

    [Fact]
    public void LaufendeAufzeichnungBleibtGeschuetzt()
    {
        using var store = NewStore();
        store.Apply(new[] { Session("open", 1_000, null, 5_000) });

        // Offene Sessions sind bewusst nicht löschbar — sie werden gerade
        // geschrieben und kämen beim nächsten Heartbeat zurück.
        Assert.Equal(0, store.DeleteSessionById("open"));
        Assert.Equal(1, store.CountSessions());
    }

    [Fact]
    public void UnbekannteIdLiefertNull()
    {
        using var store = NewStore();
        Assert.Equal(0, store.DeleteSessionById("gibt-es-nicht"));
    }

    [Fact]
    public void GeloeschteAufzeichnungWirdNichtMehrAnDasHandyAusgeliefert()
    {
        using var store = NewStore();
        store.Apply(new[]
        {
            Session("keep", 1_000, 5_000, 5_000),
            Session("drop", 6_000, 9_000, 9_000),
        });
        store.DeleteSessionById("drop");

        // Die Sync-Auslieferung (GET /api/sessions) basiert auf
        // QueryCompletedSessions — die gelöschte Zeile fehlt dort jetzt.
        var delivered = store.QueryCompletedSessions(0, 100);
        var s = Assert.Single(delivered);
        Assert.Equal("keep", s.Id);
    }
}

/// <summary>
/// PC-Tracker 1.5.0: Der Tracker lehnt einen manuellen Start ab, solange das
/// Handy aufzeichnet — die UI zeigt den Hinweis, und es blitzt keine Session
/// auf, die der nächste Tick sofort wieder beenden müsste.
/// </summary>
public class TrackerServicePhonePriorityTests : IDisposable
{
    private readonly string _dir;
    private readonly string _dbPath;

    public TrackerServicePhonePriorityTests()
    {
        _dir = Path.Combine(Path.GetTempPath(), "aevum-pctracker-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_dir);
        _dbPath = Path.Combine(_dir, "test.sqlite");
    }

    public void Dispose()
    {
        try
        {
            Directory.Delete(_dir, recursive: true);
        }
        catch
        {
            // Aufräumen ist best effort.
        }
    }

    private SessionStore NewStore()
    {
        var store = new SessionStore(_dbPath);
        store.Open();
        return store;
    }

    [Fact]
    public void ManuellerStartWirdVomHandyBlockiert()
    {
        using var store = NewStore();
        var clock = new FakeClock();
        var settings = new SettingsService(store, clock);
        var tracker = new TrackerService(new FakeProbe(), store, settings, clock,
            phoneGate: _ => PhoneGateVerdict.Recording);

        Assert.Equal(ManualStartOutcome.BlockedByPhone, tracker.StartManualActivity("arbeit"));
        Assert.Null(tracker.ActiveSession);
        Assert.True(tracker.IsPhoneRecording);
    }

    [Fact]
    public void OhneHandyAufzeichnungStartetDerManuelleStartNormal()
    {
        using var store = NewStore();
        var clock = new FakeClock();
        var settings = new SettingsService(store, clock);
        // PC 1.6.0: Der Start braucht eine frische „nichts läuft"-Bestätigung.
        var tracker = new TrackerService(new FakeProbe(), store, settings, clock,
            phoneGate: _ => PhoneGateVerdict.VerifiedIdle);

        Assert.Equal(ManualStartOutcome.Started, tracker.StartManualActivity("arbeit"));
        Assert.NotNull(tracker.ActiveSession);
        Assert.False(tracker.IsPhoneRecording);
    }

    /// <summary>
    /// PC 1.6.0: Ohne frische Bestätigung („Stand nicht verifiziert") lehnt
    /// auch der manuelle Start ab — mit eigenem Grund, die UI erklärt ihn.
    /// </summary>
    [Fact]
    public void ManuellerStartOhneVerifikationWirdAbgelehnt()
    {
        using var store = NewStore();
        var clock = new FakeClock();
        var settings = new SettingsService(store, clock);
        var tracker = new TrackerService(new FakeProbe(), store, settings, clock,
            phoneGate: _ => PhoneGateVerdict.Unverified);

        Assert.Equal(ManualStartOutcome.Unverified, tracker.StartManualActivity("arbeit"));
        Assert.Null(tracker.ActiveSession);
    }

    /// <summary>
    /// PC 1.6.0: Ganz ohne Handy-Kopplung ist das Gate aus (NotCoupled) —
    /// der PC zeichnet wie immer auf.
    /// </summary>
    [Fact]
    public void OhneKopplungStartetDerManuelleStartNormal()
    {
        using var store = NewStore();
        var clock = new FakeClock();
        var settings = new SettingsService(store, clock);
        var tracker = new TrackerService(new FakeProbe(), store, settings, clock,
            phoneGate: _ => PhoneGateVerdict.NotCoupled);

        Assert.Equal(ManualStartOutcome.Started, tracker.StartManualActivity("arbeit"));
        Assert.NotNull(tracker.ActiveSession);
    }
}
