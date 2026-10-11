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
    public List<RunningProgram> Running { get; } = new();

    public ForegroundSample? GetForeground() => Foreground;

    public long GetIdleMs() => IdleMs;

    public IReadOnlyList<RunningProgram> GetRunningPrograms() => Running;

    public RunningProgram AddRunning(string key, string? title = null)
    {
        var program = new RunningProgram
        {
            ProgramKey = key,
            Name = key,
            Title = title ?? key,
        };
        Running.Add(program);
        return program;
    }

    public void RemoveRunning(string key) => Running.RemoveAll(r =>
        string.Equals(r.ProgramKey, key, StringComparison.OrdinalIgnoreCase));

    public static ForegroundSample Sample(string key, string? path = null) => new()
    {
        ProgramKey = key,
        DisplayName = key,
        Path = path ?? @"C:\apps\" + key,
    };
}
