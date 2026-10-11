using Aevum.PcTracker.Core.Models;
using Aevum.PcTracker.Localization;

namespace Aevum.PcTracker.ViewModels;

/// <summary>Eine Session-Zeile (Dashboard „Letzte Aufzeichnungen" und Verlauf).</summary>
public sealed class SessionRowViewModel
{
    public string Icon { get; }
    public string Title { get; }
    public string Subtitle { get; }
    public string TimeText { get; }
    public string DurationText { get; }
    public bool IsOpen { get; }

    private SessionRowViewModel(string icon, string title, string subtitle, string timeText, string durationText, bool isOpen)
    {
        Icon = icon;
        Title = title;
        Subtitle = subtitle;
        TimeText = timeText;
        DurationText = durationText;
        IsOpen = isOpen;
    }

    public static SessionRowViewModel From(
        TrackedSession session,
        long nowMs,
        IReadOnlyDictionary<string, SyncedActivity>? synced = null)
    {
        var (icon, name) = ActivityDisplay(session.ActivityKey, synced);
        var start = UiFormat.Clock(session.StartAtMs);
        var durationMs = session.IsOpen ? nowMs - session.StartAtMs : session.DurationMs;
        var when = session.IsOpen
            ? Loc.F("s.row_since", start)
            : $"{start} – {UiFormat.Clock(session.EndAtMs ?? session.LastSeenAtMs)}";
        return new SessionRowViewModel(
            icon,
            name,
            session.ProgramName,
            when,
            UiFormat.Duration(durationMs),
            session.IsOpen);
    }

    /// <summary>Aktivität mit Icon — vom Handy synchronisierte zuerst, sonst Katalog.</summary>
    public static (string Icon, string Name) ActivityDisplay(
        string activityKey,
        IReadOnlyDictionary<string, SyncedActivity>? synced)
    {
        if (synced is not null && synced.TryGetValue(activityKey, out var s))
        {
            return (string.IsNullOrEmpty(s.Icon) ? "•" : s.Icon, s.Name);
        }
        var catalog = ActivityCatalog.ByKeyOrOther(activityKey);
        return (catalog.Icon, catalog.Name);
    }
}
