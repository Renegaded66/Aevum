using System.Collections.ObjectModel;
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

    [ObservableProperty] private int _rangeIndex; // 0 = heute, 1 = 7 Tage, 2 = 30 Tage
    [ObservableProperty] private bool _isEmpty;
    [ObservableProperty] private string _summaryText = "";

    public ObservableCollection<DayGroupViewModel> Days { get; } = new();

    public HistoryViewModel(AppHost host)
    {
        _host = host;
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
            _ => dayStart - 29 * DayMs,
        };

        var sessions = _host.Store.QueryCompletedSessions(since, 3000)
            .OrderByDescending(s => s.StartAtMs)
            .ToList();

        Days.Clear();
        var today = DateTime.Today;
        foreach (var group in sessions.GroupBy(s =>
                     DateTimeOffset.FromUnixTimeMilliseconds(s.StartAtMs).ToLocalTime().Date))
        {
            var list = group.ToList();
            var total = list.Sum(s => s.DurationMs);
            Days.Add(new DayGroupViewModel(
                UiFormat.DayLabel(group.Key, today),
                $"{UiFormat.Duration(total)} · {list.Count} Aufzeichnung{(list.Count == 1 ? "" : "en")}",
                list.Select(s => SessionRowViewModel.From(s, now)).ToList()));
        }

        IsEmpty = Days.Count == 0;
        SummaryText = sessions.Count == 0
            ? ""
            : $"{sessions.Count} Aufzeichnungen · {UiFormat.Duration(sessions.Sum(s => s.DurationMs))} gesamt";
    }
}
