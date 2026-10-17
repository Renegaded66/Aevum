using Aevum.PcTracker.Core.Models;
using Aevum.PcTracker.Core.Services;
using Aevum.PcTracker.Localization;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;

namespace Aevum.PcTracker.ViewModels;

/// <summary>
/// Eine Session-Zeile (Dashboard „Letzte Aufzeichnungen" und Verlauf).
///
/// PC 1.5.0: Im Verlauf kann eine abgeschlossene Aufzeichnung entfernt werden
/// (Devon: „Ich will auch in der desktop app aufzeichnungen aus dem verlauf
/// entfernen können."). Die Löschung läuft zweistufig (Inline-Bestätigung,
/// gleiches Muster wie „Zugang zurücksetzen" im Pairing) — ein Fehlklick soll
/// keine Aufzeichnung verlieren. Wird die Zeile ohne <c>onDelete</c> erzeugt
/// (Dashboard), gibt es keinen Lösch-Knopf.
/// </summary>
public sealed partial class SessionRowViewModel : ObservableObject
{
    public string Icon { get; }
    public string Title { get; }
    public string Subtitle { get; }
    public string TimeText { get; }
    public string DurationText { get; }
    public bool IsOpen { get; }

    /// <summary>ID der Session — der Anker fürs Löschen.</summary>
    public string SessionId { get; }

    private readonly Action<SessionRowViewModel>? _onDelete;

    /// <summary>Löschbar? (nur im Verlauf, nie für laufende Sessions)</summary>
    public bool CanDelete => _onDelete is not null && !IsOpen;

    [ObservableProperty] private bool _confirmDeleteVisible;

    private SessionRowViewModel(
        string sessionId,
        string icon,
        string title,
        string subtitle,
        string timeText,
        string durationText,
        bool isOpen,
        Action<SessionRowViewModel>? onDelete)
    {
        SessionId = sessionId;
        Icon = icon;
        Title = title;
        Subtitle = subtitle;
        TimeText = timeText;
        DurationText = durationText;
        IsOpen = isOpen;
        _onDelete = onDelete;
    }

    public static SessionRowViewModel From(
        TrackedSession session,
        long nowMs,
        IReadOnlyDictionary<string, SyncedActivity>? synced = null,
        Action<SessionRowViewModel>? onDelete = null)
    {
        var (icon, name) = ActivityDisplay(session.ActivityKey, synced);
        var start = UiFormat.Clock(session.StartAtMs);
        var durationMs = session.IsOpen ? nowMs - session.StartAtMs : session.DurationMs;
        var when = session.IsOpen
            ? Loc.F("s.row_since", start)
            : $"{start} – {UiFormat.Clock(session.EndAtMs ?? session.LastSeenAtMs)}";
        // PC 1.3.0: Sonder-Keys lokalisiert anzeigen („Manuell gestartet"/„PC wird
        // benutzt") — die Datenbank behält bewusst die sprachneutralen Werte.
        var subtitle = session.ProgramKey switch
        {
            SessionEngine.ManualProgramKey => Loc.S("s.man_started"),
            SessionEngine.GeneralProgramKey => Loc.S("s.gen_in_use"),
            _ => session.ProgramName,
        };
        return new SessionRowViewModel(
            session.Id,
            icon,
            name,
            subtitle,
            when,
            UiFormat.Duration(durationMs),
            session.IsOpen,
            onDelete);
    }

    /// <summary>Erster Klick: Bestätigung einblenden (kein sofortiges Löschen).</summary>
    [RelayCommand]
    private void RequestDelete()
    {
        if (CanDelete)
        {
            ConfirmDeleteVisible = true;
        }
    }

    /// <summary>„Ja": wirklich entfernen (der Verlauf lädt danach neu).</summary>
    [RelayCommand]
    private void ConfirmDelete()
    {
        ConfirmDeleteVisible = false;
        _onDelete?.Invoke(this);
    }

    [RelayCommand]
    private void CancelDelete() => ConfirmDeleteVisible = false;

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
