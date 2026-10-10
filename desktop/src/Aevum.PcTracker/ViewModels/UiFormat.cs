using System.Globalization;

namespace Aevum.PcTracker.ViewModels;

/// <summary>Kleine Anzeige-Helfer für Zeitangaben (deutsch, kompakt).</summary>
public static class UiFormat
{
    public static string Duration(long ms)
    {
        var t = TimeSpan.FromMilliseconds(Math.Max(0, ms));
        if (t.TotalHours >= 1)
        {
            return $"{(int)t.TotalHours} Std {t.Minutes} Min";
        }
        if (t.TotalMinutes >= 1)
        {
            return $"{t.Minutes} Min";
        }
        return "< 1 Min";
    }

    public static string Clock(long ms) =>
        DateTimeOffset.FromUnixTimeMilliseconds(ms).ToLocalTime().ToString("HH:mm", CultureInfo.CurrentCulture);

    public static string Ago(long thenMs, long nowMs)
    {
        var d = Math.Max(0, nowMs - thenMs);
        if (d < 60_000)
        {
            return "gerade eben";
        }
        if (d < 3_600_000)
        {
            return $"vor {(int)(d / 60_000)} Min";
        }
        if (d < 86_400_000)
        {
            return $"vor {(int)(d / 3_600_000)} Std";
        }
        return $"vor {(int)(d / 86_400_000)} Tagen";
    }

    public static long StartOfLocalDayMs(long nowMs)
    {
        var local = DateTimeOffset.FromUnixTimeMilliseconds(nowMs).ToLocalTime();
        var date = local.Date; // Kind=Unspecified, gleiche Wanduhr
        var offset = TimeZoneInfo.Local.GetUtcOffset(date);
        return new DateTimeOffset(date, offset).ToUnixTimeMilliseconds();
    }

    public static string DayLabel(DateTime date, DateTime today)
    {
        if (date.Date == today.Date)
        {
            return "Heute";
        }
        if (date.Date == today.Date.AddDays(-1))
        {
            return "Gestern";
        }
        return date.ToString("dddd, d. MMM", CultureInfo.CurrentCulture);
    }
}
