using Aevum.PcTracker.Core.Services;
using Xunit;

namespace Aevum.PcTracker.Core.Tests;

public class PairingManagerTests
{
    private const long T0 = 1_000_000;

    [Fact]
    public void Enable_GeneratesSixDigitCode()
    {
        var pairing = new PairingManager();
        Assert.False(pairing.Enabled);

        var code = pairing.Enable();

        Assert.True(pairing.Enabled);
        Assert.Matches("^[0-9]{6}$", code);
        Assert.Equal(code, pairing.CurrentCode);
    }

    [Fact]
    public void CorrectCode_SucceedsAndRotates()
    {
        var pairing = new PairingManager();
        var code = pairing.Enable();

        var result = pairing.TryPair(code, T0);

        Assert.Equal(PairOutcome.Success, result.Outcome);
        Assert.NotEqual(code, pairing.CurrentCode); // nach Erfolg rotiert
        Assert.Equal(PairOutcome.Invalid, pairing.TryPair(code, T0).Outcome); // alter Code tot
        Assert.Equal(PairOutcome.Success, pairing.TryPair(pairing.CurrentCode, T0).Outcome);
    }

    [Fact]
    public void CodeWithSpaces_IsAccepted()
    {
        var pairing = new PairingManager();
        var code = pairing.Enable();
        var spaced = code[..3] + " " + code[3..];

        Assert.Equal(PairOutcome.Success, pairing.TryPair(spaced, T0).Outcome);
    }

    [Fact]
    public void WrongCode_IsInvalid()
    {
        var pairing = new PairingManager();
        pairing.Enable();

        Assert.Equal(PairOutcome.Invalid, pairing.TryPair("000000", T0).Outcome);
        Assert.Equal(PairOutcome.Invalid, pairing.TryPair(null, T0).Outcome);
        Assert.Equal(PairOutcome.Invalid, pairing.TryPair("abc", T0).Outcome);
    }

    [Fact]
    public void FiveFailures_LockEvenTheCorrectCode()
    {
        var pairing = new PairingManager();
        var code = pairing.Enable();

        for (var i = 0; i < 4; i++)
        {
            Assert.Equal(PairOutcome.Invalid, pairing.TryPair("000000", T0 + i).Outcome);
        }
        var fifth = pairing.TryPair("000000", T0 + 4);
        Assert.Equal(PairOutcome.Locked, fifth.Outcome);
        Assert.Equal(PairingManager.LockDurationSeconds, fifth.RetryAfterSeconds);

        // Während der Sperre wird auch der richtige Code abgewiesen.
        var duringLock = pairing.TryPair(code, T0 + 10);
        Assert.Equal(PairOutcome.Locked, duringLock.Outcome);
        Assert.True(duringLock.RetryAfterSeconds > 0);

        // Nach Ablauf der Sperre (Lock begann beim fünften Fehlversuch bei T0+4)
        // geht der richtige Code wieder.
        var after = pairing.TryPair(code, T0 + 4 + PairingManager.LockDurationSeconds * 1000L + 1);
        Assert.Equal(PairOutcome.Success, after.Outcome);
    }

    [Fact]
    public void FailuresExpireOutsideWindow()
    {
        var pairing = new PairingManager();
        var code = pairing.Enable();

        for (var i = 0; i < 4; i++)
        {
            pairing.TryPair("000000", T0 + i);
        }
        // 6 Minuten später: die alten Fehlversuche zählen nicht mehr.
        var late = pairing.TryPair("000000", T0 + PairingManager.FailureWindowMs + 60_000);
        Assert.Equal(PairOutcome.Invalid, late.Outcome);

        Assert.Equal(PairOutcome.Success, pairing.TryPair(code, T0 + PairingManager.FailureWindowMs + 61_000).Outcome);
    }

    [Fact]
    public void Disabled_ReturnsNotActive()
    {
        var pairing = new PairingManager();
        Assert.Equal(PairOutcome.NotActive, pairing.TryPair("123456", T0).Outcome);

        pairing.Enable();
        pairing.Disable();
        Assert.False(pairing.Enabled);
        Assert.Equal("", pairing.CurrentCode);
        Assert.Equal(PairOutcome.NotActive, pairing.TryPair("123456", T0).Outcome);
    }

    [Theory]
    [InlineData("482 913", "482913")]
    [InlineData("482-913", "482913")]
    [InlineData("482913", "482913")]
    [InlineData("", "")]
    [InlineData(null, "")]
    [InlineData("abcdef", "")]
    public void Normalize_StripsNonDigits(string? input, string expected)
    {
        Assert.Equal(expected, PairingManager.Normalize(input));
    }

    [Fact]
    public void Normalize_RejectsOverlongInput()
    {
        Assert.Equal("", PairingManager.Normalize(new string('1', 100)));
    }
}
