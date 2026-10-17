using Aevum.PcTracker.Core.Models;
using Aevum.PcTracker.Core.Services;
using Xunit;

namespace Aevum.PcTracker.Core.Tests;

/// <summary>
/// PC-Tracker 1.6.0 — VERIFIKATIONS-GATE. Devon: „Am besten sollte die
/// desktop app erst erlauben, überhaupt irgendwas aufzuzeichnen […] wenn die
/// desktop app verifiziert hat, das auf dem handy nichts läuft, ansonsten
/// kommt eine meldung das der stand des handys nicht verifiziert werden
/// konnte. Damit das problem nicht weiterhin besteht."
///
/// Diese Testklasse nagelt fest:
///  • Ohne frische „nichts läuft"-Bestätigung startet NICHTS Neues
///    (weder Regel- noch allgemeine Aufzeichnung) — fail-closed.
///  • Laufende Sessions bleiben am Leben (nur der frische Handy-Vorrang beendet).
///  • Nach der Bestätigung startet nichts rückwirkend (Sichtbarkeit ab jetzt).
///  • Das Gate ist aus (NotCoupled), solange nie ein Handy gekoppelt war.
/// </summary>
public class PhoneVerificationGateTests
{
    private const string TZ = "Europe/Berlin";
    private int _idCounter;

    private SessionEngine NewEngine() => new(() => $"p{++_idCounter}");

    private static TrackingRule Rule(string key, int thresholdSec = 60) => new()
    {
        ProgramKey = key,
        ProgramName = key,
        ActivityKey = "gaming",
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
        bool verifiedIdle,
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
        PhoneRecording = false,
        PhoneVerifiedIdle = verifiedIdle,
    };

    [Fact]
    public void OhneVerifikationStartetKeineRegelSession()
    {
        var engine = NewEngine();
        var rules = new[] { Rule("minecraft.exe") };

        // Programm sichtbar, Regel aktiv — aber keine frische Bestätigung.
        var upserts = engine.Tick(Tick(1_000, Fg("minecraft.exe"), rules, verifiedIdle: false));

        Assert.Empty(upserts);
        Assert.Null(engine.Active);
    }

    [Fact]
    public void NachDerVerifikationStartetDieSession_NichtRueckwirkend()
    {
        var engine = NewEngine();
        var rules = new[] { Rule("minecraft.exe") };

        engine.Tick(Tick(1_000, Fg("minecraft.exe"), rules, verifiedIdle: false));
        engine.Tick(Tick(3_000, Fg("minecraft.exe"), rules, verifiedIdle: false));

        // Frische Bestätigung „am Handy läuft nichts" → Start ab dem letzten
        // geblockten Moment (Sichtbarkeit zählt ab jetzt), nicht ab 1_000.
        var upserts = engine.Tick(Tick(7_000, Fg("minecraft.exe"), rules, verifiedIdle: true));

        var started = Assert.Single(upserts);
        Assert.Equal(3_000, started.StartAtMs);
        Assert.NotNull(engine.Active);
    }

    [Fact]
    public void OhneVerifikationStartetKeineAllgemeineAufzeichnung()
    {
        var engine = NewEngine();

        var upserts = engine.Tick(Tick(1_000, null, Array.Empty<TrackingRule>(),
            verifiedIdle: false, generalEnabled: true, generalActivity: "digital"));

        Assert.Empty(upserts);
        Assert.Null(engine.Active);

        // Bestätigung da → die allgemeine Aufzeichnung startet mit dem
        // Moment der Bestätigung (nicht rückwirkend).
        var then = engine.Tick(Tick(5_000, null, Array.Empty<TrackingRule>(),
            verifiedIdle: true, generalEnabled: true, generalActivity: "digital"));
        var started = Assert.Single(then);
        Assert.Equal(SessionEngine.GeneralProgramKey, started.ProgramKey);
        Assert.Equal(5_000, started.StartAtMs);
    }

    [Fact]
    public void LaufendeRegelSessionUeberlebtUnverifizierteTicks()
    {
        var engine = NewEngine();
        var rules = new[] { Rule("minecraft.exe") };

        // Start bei verifiziertem Stand …
        engine.Tick(Tick(1_000, Fg("minecraft.exe"), rules, verifiedIdle: true));
        Assert.NotNull(engine.Active);

        // … dann reißt die Bestätigung ab (Sync-Verzögerung): Die Session
        // läuft weiter (nur der frische Vorrang beendet) und wird beatmet.
        var upserts = engine.Tick(Tick(3_000, Fg("minecraft.exe"), rules, verifiedIdle: false));

        var heartbeat = Assert.Single(upserts);
        Assert.Null(heartbeat.EndAtMs);
        Assert.Equal(3_000, heartbeat.LastSeenAtMs);
        Assert.NotNull(engine.Active);
    }

    [Fact]
    public void UnverifiziertBeendetKeineManuelleSession()
    {
        var engine = NewEngine();
        engine.StartManual("work", TZ, 1_000);

        var upserts = engine.Tick(Tick(5_000, null, Array.Empty<TrackingRule>(), verifiedIdle: false));

        var heartbeat = Assert.Single(upserts);
        Assert.Equal(SessionEngine.ManualProgramKey, heartbeat.ProgramKey);
        Assert.Null(heartbeat.EndAtMs);
        Assert.NotNull(engine.Active);
    }
}

/// <summary>
/// PC 1.6.0: Das Gate-Urteil der PhoneLiveRegistry — fail-closed:
/// Ein veraltetes „zeichnet auf" zählt NIE als frei; nur eine frische
/// „nichts läuft"-Bestätigung (session:null, auch als Sync-Ping) gibt frei.
/// </summary>
public class PhoneGateVerdictTests
{
    private static PhoneLiveSession Session() => new()
    {
        SessionId = "s1",
        ActivityKey = "study",
        ActivityName = "Studium",
        Icon = "📚",
        CategoryKey = "learning",
        StartAtMs = 1_000,
        IsPaused = false,
        BlockEndAtMs = 0,
        LastUpdateMs = 0,
    };

    [Fact]
    public void OhneKopplungIstDasGateAus()
    {
        var reg = new PhoneLiveRegistry();
        // coupled=false → der PC zeichnet wie immer auf (kein Handy in dieser Installation).
        Assert.Equal(PhoneGateVerdict.NotCoupled, reg.GetGateVerdict(10_000, coupled: false));
    }

    [Fact]
    public void NieGemeldet_IstUnverifiziert()
    {
        var reg = new PhoneLiveRegistry();
        Assert.Equal(PhoneGateVerdict.Unverified, reg.GetGateVerdict(10_000, coupled: true));
    }

    [Fact]
    public void FrischeAufzeichnung_Blockiert()
    {
        var reg = new PhoneLiveRegistry();
        reg.HandleRelay(Session(), 10_000);

        Assert.Equal(PhoneGateVerdict.Recording, reg.GetGateVerdict(12_000, coupled: true));
        // Prioritätsfenster (5 min): auch exakt an der Grenze noch Recording.
        Assert.Equal(PhoneGateVerdict.Recording,
            reg.GetGateVerdict(10_000 + PhoneLiveRegistry.PriorityWindowMs, coupled: true));
    }

    /// <summary>
    /// Der Kern der Forderung: Ein veraltetes „zeichnet auf" ist KEIN
    /// Freibrief — der PC bleibt bei „nicht verifiziert" (fail-closed) und
    /// startet nichts. Genau das beendet die Minuten-Falschaufzeichnungen.
    /// </summary>
    [Fact]
    public void VeralteteAufzeichnung_IstUnverifiziert_NichtFrei()
    {
        var reg = new PhoneLiveRegistry();
        reg.HandleRelay(Session(), 10_000);

        // Knapp über dem Prioritätsfenster: NICHT frei, sondern unverifiziert.
        Assert.Equal(PhoneGateVerdict.Unverified,
            reg.GetGateVerdict(10_000 + PhoneLiveRegistry.PriorityWindowMs + 1, coupled: true));
        // Und auch nach sehr langer Funkstille bleibt es unverifiziert
        // (kein „stilles" Freiwerden wie in 1.5.x).
        Assert.Equal(PhoneGateVerdict.Unverified, reg.GetGateVerdict(10_000 + 3_600_000, coupled: true));
    }

    [Fact]
    public void AusdruecklicheLeerlaufBestaetigung_GibtFrei()
    {
        var reg = new PhoneLiveRegistry();
        reg.HandleRelay(null, 10_000);

        Assert.Equal(PhoneGateVerdict.VerifiedIdle, reg.GetGateVerdict(12_000, coupled: true));
        // Innerhalb des Bestätigungsfensters (30 min) frisch genug.
        Assert.Equal(PhoneGateVerdict.VerifiedIdle,
            reg.GetGateVerdict(10_000 + PhoneLiveRegistry.IdleVerificationWindowMs, coupled: true));
        // Danach: nicht mehr frisch → wieder unverifiziert (kein stilles Weiterlaufen).
        Assert.Equal(PhoneGateVerdict.Unverified,
            reg.GetGateVerdict(10_000 + PhoneLiveRegistry.IdleVerificationWindowMs + 1, coupled: true));
    }

    [Fact]
    public void WiederholtePings_FrischenDieBestaetigungAuf()
    {
        var reg = new PhoneLiveRegistry();
        reg.HandleRelay(null, 10_000);
        reg.HandleRelay(null, 25 * 60_000); // Sync-Ping nach ~25 min

        Assert.Equal(PhoneGateVerdict.VerifiedIdle, reg.GetGateVerdict(25 * 60_000 + 60_000, coupled: true));
    }

    [Fact]
    public void AufzeichnungBeendet_UndDannBestaetigt_WiederFrei()
    {
        var reg = new PhoneLiveRegistry();
        reg.HandleRelay(Session(), 10_000);
        Assert.Equal(PhoneGateVerdict.Recording, reg.GetGateVerdict(10_000, coupled: true));

        // Handy beendet die Aufzeichnung ausdrücklich.
        reg.HandleRelay(null, 20_000);
        Assert.Equal(PhoneGateVerdict.VerifiedIdle, reg.GetGateVerdict(20_000, coupled: true));
    }

    [Fact]
    public void Status_LiefertIdleBestaetigungsAlter()
    {
        var reg = new PhoneLiveRegistry();
        Assert.Equal(-1, reg.GetStatus(10_000).IdleVerifiedAgeMs);

        reg.HandleRelay(null, 10_000);
        Assert.Equal(5_000, reg.GetStatus(15_000).IdleVerifiedAgeMs);
    }
}
