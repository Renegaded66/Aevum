using Aevum.PcTracker.Core.Models;

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

    public static SessionRowViewModel From(TrackedSession session, long nowMs)
    {
        var activity = ActivityCatalog.ByKeyOrOther(session.ActivityKey);
        var start = UiFormat.Clock(session.StartAtMs);
        var durationMs = session.IsOpen ? nowMs - session.StartAtMs : session.DurationMs;
        var when = session.IsOpen
            ? $"seit {start}"
            : $"{start} – {UiFormat.Clock(session.EndAtMs ?? session.LastSeenAtMs)}";
        return new SessionRowViewModel(
            activity.Icon,
            activity.Name,
            session.ProgramName,
            when,
            UiFormat.Duration(durationMs),
            session.IsOpen);
    }
}
