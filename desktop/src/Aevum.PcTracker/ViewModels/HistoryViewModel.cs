using System.Collections.ObjectModel;
using Aevum.PcTracker.Core.Models;
using Aevum.PcTracker.Localization;
using Aevum.PcTracker.Services;
using CommunityToolkit.Mvvm.ComponentModel;

namespace Aevum.PcTracker.ViewModels;

public sealed class DayGroupViewModel
{
    public string Header { get; }
    public string Summary { get; }
    public IReadOnlyList<SessionRowViewModel> Sessions { get; }

    public DayGroupViewModel(string header, string summary, IReadOnlyList<SessionRowViewModel> sessions)
    {
        Header = header;
        Summary = summary;
        Sessions = sessions;
    }
}

public partial class HistoryViewModel : ObservableObject, IRefreshable
{
    private const long DayMs = 86_400_000;

    private readonly AppHost _host;

    /// <summary>0 = heute, 1 = 7 Tage, 2 = 30 Tage, 3 = gesamt.</summary>
    [ObservableProperty] private int _rangeIndex;
    [ObservableProperty] private bool _isEmpty;
    [ObservableProperty] private string _summaryText = "";

    public ObservableCollection<DayGroupViewModel> Days { get; } = new();

    public HistoryViewModel(AppHost host)
    {
        _host = host;
        Loc.LanguageChanged += () => Avalonia.Threading.Dispatcher.UIThread.Post(Refresh);
        Refresh();
    }

    public void Refresh() => Reload();

    partial void OnRangeIndexChanged(int value) => Reload();

    private void Reload()
    {
        var now = _host.Clock.NowMs;
        var dayStart = UiFormat.StartOfLocalDayMs(now);
        var since = RangeIndex switch
        {
            0 => dayStart,
            1 => dayStart - 6 * DayMs,
            2 => dayStart - 29 * DayMs,
            _ => 0L,
        };

        var sessions = _host.Store.QueryCompletedSessions(since, 3000)
            .OrderByDescending(s => s.StartAtMs)
            .ToList();

        var syncedLookup = _host.Store.GetSyncedActivities()
            .ToDictionary(a => a.Key, StringComparer.OrdinalIgnoreCase);

        Days.Clear();
        var today = DateTime.Today;
        // PC 1.5.0: Verlaufs-Zeilen sind löschbar — der Handler entfernt die
        // Session (nur abgeschlossene) und lädt die Ansicht neu.
        Action<SessionRowViewModel> onDelete = row =>
        {
            _host.Store.DeleteSessionById(row.SessionId);
            Reload();
        };
        foreach (var group in sessions.GroupBy(s =>
                     DateTimeOffset.FromUnixTimeMilliseconds(s.StartAtMs).ToLocalTime().Date))
        {
            var list = group.ToList();
            var total = list.Sum(s => s.DurationMs);
            Days.Add(new DayGroupViewModel(
                UiFormat.DayLabel(group.Key, today),
                $"{Recordings(list.Count)} · {UiFormat.Duration(total)}",
                list.Select(s => SessionRowViewModel.From(s, now, syncedLookup, onDelete)).ToList()));
        }

        IsEmpty = Days.Count == 0;
        SummaryText = sessions.Count == 0
            ? ""
            : Loc.F("s.hist_summary", Recordings(sessions.Count), UiFormat.Duration(sessions.Sum(s => s.DurationMs)));
    }

    private static string Recordings(int count) =>
        count == 1 ? Loc.S("s.rec_1") : Loc.F("s.rec_n", count);
}
