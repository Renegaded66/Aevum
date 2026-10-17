using Aevum.PcTracker.Core.Models;
using Aevum.PcTracker.Core.Services;
using Xunit;

namespace Aevum.PcTracker.Core.Tests;

/// <summary>
/// PC 1.4.0: Der Live-Kanal zum Handy (PhoneLiveRegistry) — reine Logik.
///
/// Regeln, die hier festgenagelt werden:
///  • Meldungen stempeln den Empfang; ohne Meldung wird der Zustand stale.
///  • Ein Befehl wird bei JEDER Meldung erneut geliefert, bis der
///    Folgezustand ihn bestätigt (Pause→isPaused, Stop→keine Session).
///  • „Fortsetzen" bestätigt sich über „läuft wieder" — die App startet
///    dabei eine NEUE Session (M18.62-Split), die ID wechselt bewusst.
///  • Nach TTL ohne Bestätigung wird ehrlich aufgegeben (kein Zombie).
///  • Nach langer Funkstille (GoneAfter) verschwindet die Session ganz.
/// </summary>
public class PhoneLiveRegistryTests
{
    private static PhoneLiveSession Session(string id = "s1", bool paused = false, long startAt = 1_000) => new()
    {
        SessionId = id,
        ActivityKey = "study",
        ActivityName = "Studium",
        Icon = "📚",
        CategoryKey = "learning",
        StartAtMs = startAt,
        IsPaused = paused,
        BlockEndAtMs = paused ? 5_000 : 0,
        LastUpdateMs = 0, // stempelt der Server
    };

    [Fact]
    public void Relay_StampsUpdate_AndDeliversNoCommand()
    {
        var reg = new PhoneLiveRegistry();

        var command = reg.HandleRelay(Session(), 10_000);

        Assert.Null(command);
        var status = reg.GetStatus(12_000);
        Assert.NotNull(status.Session);
        Assert.Equal("Studium", status.Session!.ActivityName);
        Assert.Equal(10_000, status.Session.LastUpdateMs);
        Assert.False(status.Stale);
    }

    [Fact]
    public void Status_BecomesStale_WithoutFreshUpdates()
    {
        var reg = new PhoneLiveRegistry();
        reg.HandleRelay(Session(), 10_000);

        Assert.False(reg.GetStatus(10_000 + PhoneLiveRegistry.StaleAfterMs - 1).Stale);
        Assert.True(reg.GetStatus(10_000 + PhoneLiveRegistry.StaleAfterMs + 1).Stale);
    }

    [Fact]
    public void Pause_IsDeliveredUntilConfirmed()
    {
        var reg = new PhoneLiveRegistry();
        reg.HandleRelay(Session(), 10_000);

        reg.RequestCommand(PhoneLiveCommand.Pause, 11_000);

        // Lieferung 1 + erneute Lieferung (Antwort verloren? nächster Poll).
        Assert.Equal(PhoneLiveCommand.Pause, reg.HandleRelay(Session(), 12_000));
        Assert.Equal(PhoneLiveCommand.Pause, reg.HandleRelay(Session(), 14_000));

        // Bestätigung: Handy meldet pausiert.
        Assert.Null(reg.HandleRelay(Session(paused: true), 16_000));
        Assert.Null(reg.GetStatus(16_000).PendingCommand);
    }

    [Fact]
    public void Resume_ConfirmsOnRunningState_EvenWithNewSessionId()
    {
        var reg = new PhoneLiveRegistry();
        reg.HandleRelay(Session(id: "old", paused: true), 10_000);
        reg.RequestCommand(PhoneLiveCommand.Resume, 11_000);

        Assert.Equal(PhoneLiveCommand.Resume, reg.HandleRelay(Session(id: "old", paused: true), 12_000));
        // M18.62-Split: „Fortsetzen" erzeugt eine NEUE Session — zählt als bestätigt.
        Assert.Null(reg.HandleRelay(Session(id: "neu", paused: false), 14_000));
    }

    [Fact]
    public void Stop_ConfirmsOnMissingSession()
    {
        var reg = new PhoneLiveRegistry();
        reg.HandleRelay(Session(), 10_000);
        reg.RequestCommand(PhoneLiveCommand.Stop, 11_000);

        Assert.Equal(PhoneLiveCommand.Stop, reg.HandleRelay(Session(), 12_000));
        Assert.Null(reg.HandleRelay(null, 13_000));
        Assert.Null(reg.GetStatus(13_000).Session);
    }

    [Fact]
    public void UnconfirmedCommand_ExpiresAfterTtl()
    {
        var reg = new PhoneLiveRegistry();
        reg.HandleRelay(Session(), 10_000);
        reg.RequestCommand(PhoneLiveCommand.Pause, 10_000);

        var late = 10_000 + PhoneLiveRegistry.CommandTtlMs + 1;
        Assert.Null(reg.HandleRelay(Session(), late)); // ehrlich aufgegeben
        Assert.Null(reg.GetStatus(late).PendingCommand);
    }

    [Fact]
    public void PendingAge_IsReportedForUi()
    {
        var reg = new PhoneLiveRegistry();
        reg.HandleRelay(Session(), 10_000);
        reg.RequestCommand(PhoneLiveCommand.Stop, 10_000);

        var status = reg.GetStatus(13_000);
        Assert.Equal(PhoneLiveCommand.Stop, status.PendingCommand);
        Assert.Equal(3_000, status.PendingAgeMs);
    }

    [Fact]
    public void Session_DisappearsAfterLongSilence()
    {
        var reg = new PhoneLiveRegistry();
        reg.HandleRelay(Session(), 10_000);

        Assert.NotNull(reg.GetStatus(10_000).Session);
        Assert.Null(reg.GetStatus(10_000 + PhoneLiveRegistry.GoneAfterMs + 1).Session);
    }

    [Fact]
    public void Command_OnStaleSession_StillDelivered_ThenExpires()
    {
        // Handy kurz offline (Funkloch), Befehl kam währenddessen an:
        // Er darf nicht verloren gehen, solange die TTL läuft.
        var reg = new PhoneLiveRegistry();
        reg.HandleRelay(Session(), 10_000);
        reg.RequestCommand(PhoneLiveCommand.Stop, 20_000); // stale-Phase (10 s alt)

        Assert.Equal(PhoneLiveCommand.Stop, reg.HandleRelay(Session(), 30_000));
    }
}
