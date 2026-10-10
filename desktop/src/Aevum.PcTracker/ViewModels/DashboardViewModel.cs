using System.Collections.ObjectModel;
using Avalonia.Media;
using Avalonia.Threading;
using Aevum.PcTracker.Core.Models;
using Aevum.PcTracker.Core.Services;
using Aevum.PcTracker.Services;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;

namespace Aevum.PcTracker.ViewModels;

/// <summary>Ein Balken in „Top-Aktivitäten" (Breite vorab berechnet, pixelgenau).</summary>
public sealed class BarRowViewModel
{
    public string Icon { get; }
    public string Title { get; }
    public string DurationText { get; }
    public double BarWidth { get; }

    public BarRowViewModel(string icon, string title, string durationText, double barWidth)
    {
        Icon = icon;
        Title = title;
        DurationText = durationText;
        BarWidth = barWidth;
    }
}

public partial class DashboardViewModel : ObservableObject, IRefreshable
{
    private const double BarMaxWidth = 200;

    private readonly AppHost _host;
    private readonly DispatcherTimer _liveTimer;

    [ObservableProperty] private bool _isPaused;
    [ObservableProperty] private bool _probeAvailable = true;
    [ObservableProperty] private string _nowIcon = "🖥";
    [ObservableProperty] private string _nowTitle = "—";
    [ObservableProperty] private string _nowDetails = "Warte auf Aktivität…";
    [ObservableProperty] private string _todayCountText = "0";
    [ObservableProperty] private string _todayTotalText = "0 Min";
    [ObservableProperty] private string _pairingStatusText = "…";
    [ObservableProperty] private string _pairingAddressText = "";
    [ObservableProperty] private IBrush _pairingDotBrush = new SolidColorBrush(Color.Parse("#9491A5"));

    public ObservableCollection<BarRowViewModel> TopBars { get; } = new();
    public ObservableCollection<SessionRowViewModel> Recent { get; } = new();

    public DashboardViewModel(AppHost host)
    {
        _host = host;
        _host.Server.ClientAccessed += () => Dispatcher.UIThread.Post(Refresh);
        _liveTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
        _liveTimer.Tick += (_, _) => UpdateLive();
        _liveTimer.Start();
        Refresh();
    }

    public void Refresh()
    {
        UpdateLive();
        ReloadToday();
        UpdatePairing();
    }

    private void UpdateLive()
    {
        var now = _host.Clock.NowMs;
        ProbeAvailable = _host.Tracker.ProbeAvailable;
        IsPaused = _host.Settings.Current.TrackingPaused;
        var fg = _host.Tracker.LastForeground;

        if (!ProbeAvailable)
        {
            NowIcon = "🖥";
            NowTitle = "—";
            NowDetails = "PC-Aufzeichnung ist nur unter Windows aktiv.";
            return;
        }
        if (IsPaused)
        {
            NowIcon = "⏸";
            NowTitle = "Pausiert";
            NowDetails = "Die Aufzeichnung ist angehalten.";
            return;
        }

        var active = _host.Tracker.ActiveSession;
        if (active is not null)
        {
            var activity = ActivityCatalog.ByKeyOrOther(active.ActivityKey);
            NowIcon = activity.Icon;
            NowTitle = activity.Name;
            NowDetails = $"{active.ProgramName} · seit {UiFormat.Clock(active.StartAtMs)} · {UiFormat.Duration(now - active.StartAtMs)}";
        }
        else if (fg is not null)
        {
            NowIcon = "🪟";
            NowTitle = "Bereit";
            NowDetails = $"{fg.DisplayName} ist offen — noch keiner Aktivität zugeordnet.";
        }
        else
        {
            NowIcon = "🖥";
            NowTitle = "—";
            NowDetails = "Kein aktives Fenster erkannt.";
        }
    }

    private void ReloadToday()
    {
        var now = _host.Clock.NowMs;
        var dayStart = UiFormat.StartOfLocalDayMs(now);
        var sessions = _host.Store.QueryRecentSessions(dayStart, 500);
        var completed = sessions.Where(s => !s.IsOpen).ToList();
        var active = _host.Tracker.ActiveSession;
        var liveMs = active is not null && active.StartAtMs >= dayStart ? now - active.StartAtMs : 0;

        var count = completed.Count + (liveMs > 0 ? 1 : 0);
        TodayCountText = count.ToString();
        var totalMs = completed.Sum(s => s.DurationMs) + liveMs;
        TodayTotalText = UiFormat.Duration(totalMs);

        // Top-Aktivitäten (max. 3) — abgeschlossene Sessions + laufende.
        var byActivity = new Dictionary<string, long>();
        foreach (var s in completed)
        {
            byActivity[s.ActivityKey] = byActivity.GetValueOrDefault(s.ActivityKey) + s.DurationMs;
        }
        if (active is not null && liveMs > 0)
        {
            byActivity[active.ActivityKey] = byActivity.GetValueOrDefault(active.ActivityKey) + liveMs;
        }
        var top = byActivity.OrderByDescending(kv => kv.Value).Take(3).ToList();
        var maxMs = top.Count > 0 ? Math.Max(1, top[0].Value) : 1;
        TopBars.Clear();
        foreach (var (key, ms) in top)
        {
            var activity = ActivityCatalog.ByKeyOrOther(key);
            var width = Math.Max(6, BarMaxWidth * ms / maxMs);
            TopBars.Add(new BarRowViewModel(activity.Icon, activity.Name, UiFormat.Duration(ms), width));
        }

        // Letzte Aufzeichnungen (laufende zuerst, dann die neuesten abgeschlossenen).
        Recent.Clear();
        if (active is not null)
        {
            Recent.Add(SessionRowViewModel.From(active, now));
        }
        foreach (var s in completed.Take(5))
        {
            Recent.Add(SessionRowViewModel.From(s, now));
        }
    }

    private void UpdatePairing()
    {
        var server = _host.Server;
        if (server.IsRunning)
        {
            var addresses = NetworkInfo.LocalIPv4Addresses();
            var host = addresses.Count > 0 ? addresses[0] : "127.0.0.1";
            PairingAddressText = $"Erreichbar unter {host}:{server.BoundPort} (gleiches WLAN)";
        }
        else
        {
            PairingAddressText = _host.ServerError is { Length: > 0 } error
                ? $"LAN-Server konnte nicht starten: {error}"
                : "LAN-Server startet…";
        }

        var last = server.LastClientAccessMs;
        if (last == 0)
        {
            PairingStatusText = "Noch kein Handy abgerufen";
            PairingDotBrush = new SolidColorBrush(Color.Parse("#9491A5"));
        }
        else
        {
            PairingStatusText = $"Handy zuletzt: {UiFormat.Ago(last, _host.Clock.NowMs)}";
            PairingDotBrush = new SolidColorBrush(Color.Parse("#5EEAD4"));
        }
    }

    [RelayCommand]
    private void OpenPairing() => RequestNavigation?.Invoke("pairing");

    /// <summary>Wird vom Hauptfenster gesetzt: Wechsel zum Kopplungs-Tab.</summary>
    public Action<string>? RequestNavigation { get; set; }
}
