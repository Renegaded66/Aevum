using Aevum.PcTracker.Core.Models;
using Aevum.PcTracker.Core.Services;

namespace Aevum.PcTracker.Core.Tests;

/// <summary>Steuerbare Zeit für deterministische Tests.</summary>
public sealed class FakeClock : IClock
{
    public long NowMs { get; set; }

    public FakeClock(long nowMs = 1_000_000)
    {
        NowMs = nowMs;
    }

    public void Advance(long ms) => NowMs += ms;
}

/// <summary>Steuerbares „Vordergrundfenster" für Tracker-Tests.</summary>
public sealed class FakeProbe : IForegroundProbe
{
    public ForegroundSample? Foreground { get; set; }
    public long IdleMs { get; set; }

    public ForegroundSample? GetForeground() => Foreground;

    public long GetIdleMs() => IdleMs;

    public static ForegroundSample Sample(string key, string? path = null) => new()
    {
        ProgramKey = key,
        DisplayName = key,
        Path = path ?? @"C:\apps\" + key,
    };
}
