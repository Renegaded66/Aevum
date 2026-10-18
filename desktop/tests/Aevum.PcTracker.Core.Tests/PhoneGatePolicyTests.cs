using Aevum.PcTracker.Core.Services;
using Xunit;

namespace Aevum.PcTracker.Core.Tests;

/// <summary>
/// PC 1.7.1: Gate-Schärfung — kein „Digital"-Schnipsel direkt nach einem
/// Update, bevor der Handy-Stand bekannt ist.
/// </summary>
public class PhoneGatePolicyTests
{
    private const long Day = 24 * 60 * 60 * 1000L;

    [Fact]
    public void KnownPhone_AlwaysArmsGate()
    {
        Assert.True(PhoneGatePolicy.IsArmed(phoneKnown: true, installationAgeMs: 0, sinceStartMs: 999_999));
    }

    [Fact]
    public void UpgradedInstallation_ArmsGateDuringStartupGrace()
    {
        // Alter Bestand, Handy noch nie verzeichnet (Upgrade aus 1.5.x).
        Assert.True(PhoneGatePolicy.IsArmed(phoneKnown: false, installationAgeMs: 30 * Day, sinceStartMs: 0));
        Assert.True(PhoneGatePolicy.IsArmed(phoneKnown: false, installationAgeMs: 30 * Day, sinceStartMs: PhoneGatePolicy.StartupGraceMs - 1));
    }

    [Fact]
    public void UpgradedInstallation_ReleasesGateAfterGrace()
    {
        Assert.False(PhoneGatePolicy.IsArmed(phoneKnown: false, installationAgeMs: 30 * Day, sinceStartMs: PhoneGatePolicy.StartupGraceMs));
        Assert.False(PhoneGatePolicy.IsArmed(phoneKnown: false, installationAgeMs: 30 * Day, sinceStartMs: 5 * 60_000));
    }

    [Fact]
    public void FreshInstallation_RecordsImmediately()
    {
        // Neuer PC ohne Handy: keine Schonfrist, sonst zeichnet er nie etwas auf.
        Assert.False(PhoneGatePolicy.IsArmed(phoneKnown: false, installationAgeMs: 2 * 60_000, sinceStartMs: 0));
    }
}
