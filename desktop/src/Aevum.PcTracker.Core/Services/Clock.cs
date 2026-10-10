namespace Aevum.PcTracker.Core.Services;

/// <summary>Zeitquelle — als Interface, damit Tests die Zeit exakt steuern können.</summary>
public interface IClock
{
    /// <summary>Unix-Millisekunden (UTC).</summary>
    long NowMs { get; }
}

public sealed class SystemClock : IClock
{
    public static readonly SystemClock Instance = new();
    public long NowMs => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
}
