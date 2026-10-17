using System.Globalization;
using Aevum.PcTracker.Localization;

namespace Aevum.PcTracker.ViewModels;

/// <summary>Kleine Anzeige-Helfer für Zeitangaben (sprachabhängig, kompakt).</summary>
public static class UiFormat
{
    public static string Duration(long ms)
    {
        var t = TimeSpan.FromMilliseconds(Math.Max(0, ms));
        if (t.TotalHours >= 1)
        {
            return Loc.IsGerman
                ? $"{(int)t.TotalHours} Std {t.Minutes} Min"
                : $"{(int)t.TotalHours} h {t.Minutes} min";
        }
        if (t.TotalMinutes >= 1)
        {
            return $"{t.Minutes} min";
        }
        return Loc.IsGerman ? "< 1 Min" : "< 1 min";
    }

    public static string Clock(long ms) =>
        DateTimeOffset.FromUnixTimeMilliseconds(ms).ToLocalTime().ToString("HH:mm", CultureInfo.CurrentCulture);

    /// <summary>
    /// PC 1.5.2: Laufende Dauer als tickende Uhr — „07:12" oder „1:07:12" —
    /// für die große Live-Anzeige im Dashboard (wie am Handy).
    /// </summary>
    public static string Timer(long ms)
    {
        var t = TimeSpan.FromMilliseconds(Math.Max(0, ms));
        return t.TotalHours >= 1
            ? $"{(int)t.TotalHours}:{t.Minutes:00}:{t.Seconds:00}"
            : $"{t.Minutes:00}:{t.Seconds:00}";
    }

    public static string Ago(long thenMs, long nowMs)
    {
        var d = Math.Max(0, nowMs - thenMs);
        if (d < 60_000)
        {
            return Loc.S("s.sync_just_now");
        }
        if (d < 3_600_000)
        {
            return Loc.F("s.sync_min_ago", (int)(d / 60_000));
        }
        if (d < 86_400_000)
        {
            var hours = (int)(d / 3_600_000);
            return Loc.IsGerman ? $"vor {hours} Std" : $"{hours} h ago";
        }
        var days = (int)(d / 86_400_000);
        return Loc.IsGerman ? $"vor {days} Tagen" : $"{days} days ago";
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
            return Loc.IsGerman ? "Heute" : "Today";
        }
        if (date.Date == today.Date.AddDays(-1))
        {
            return Loc.IsGerman ? "Gestern" : "Yesterday";
        }
        var culture = Loc.IsGerman ? CultureInfo.CurrentCulture : CultureInfo.InvariantCulture;
        return date.ToString("dddd, d. MMM", culture);
    }
}
