using System.Collections.ObjectModel;
using Avalonia.Media;
using Avalonia.Threading;
using Aevum.PcTracker.Core.Models;
using Aevum.PcTracker.Core.Services;
using Aevum.PcTracker.Localization;
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

    // „Jetzt“-Karte (mit Puls-Animation im View, solange IsRecording)
    [ObservableProperty] private bool _isRecording;
    [ObservableProperty] private string _nowLabel = "";
    [ObservableProperty] private IBrush _nowLabelBrush = new SolidColorBrush(Color.Parse("#9491A5"));
    [ObservableProperty] private string _nowIcon = "🖥";
    [ObservableProperty] private string _nowTitle = "—";
    [ObservableProperty] private string _nowLine = "";

    [ObservableProperty] private string _todayCountText = "0";
    [ObservableProperty] private string _todayTotalText = "0 min";

    // „Dein Handy“
    [ObservableProperty] private string _phoneStatusText = "";
    [ObservableProperty] private string _phoneDetailText = "";
    [ObservableProperty] private IBrush _phoneDotBrush = new SolidColorBrush(Color.Parse("#9491A5"));
    [ObservableProperty] private string _pairingHintText = "";
    [ObservableProperty] private bool _isPhoneConnected;

    public ObservableCollection<BarRowViewModel> TopBars { get; } = new();
    public ObservableCollection<SessionRowViewModel> Recent { get; } = new();

    /// <summary>Wird vom Hauptfenster gesetzt: Wechsel zum Kopplungs-Tab.</summary>
    public Action<string>? RequestNavigation { get; set; }

    public DashboardViewModel(AppHost host)
    {
        _host = host;
        _host.Server.ClientAccessed += () => Dispatcher.UIThread.Post(Refresh);
        Loc.LanguageChanged += () => Dispatcher.UIThread.Post(Refresh);
        _liveTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
        _liveTimer.Tick += (_, _) => UpdateLive();
        _liveTimer.Start();
        Refresh();
    }

    public void Refresh()
    {
        UpdateLive();
        ReloadToday();
        UpdatePhone();
    }

    /// <summary>Aktivitätsname+Icon aus Sicht der App: synchronisierte zuerst, sonst Katalog.</summary>
    private (string Icon, string Name) ActivityDisplay(string activityKey)
    {
        var synced = _host.Store.GetSyncedActivities()
            .ToDictionary(a => a.Key, StringComparer.OrdinalIgnoreCase);
        return SessionRowViewModel.ActivityDisplay(activityKey, synced);
    }

    private void UpdateLive()
    {
        var now = _host.Clock.NowMs;
        ProbeAvailable = _host.Tracker.ProbeAvailable;
        IsPaused = _host.Settings.Current.TrackingPaused;

        if (!ProbeAvailable)
        {
            IsRecording = false;
            NowLabel = Loc.S("s.dash_now_idle");
            NowLabelBrush = IdleBrush;
            NowIcon = "🖥";
            NowTitle = "—";
            NowLine = Loc.S("s.dash_windows_only");
            return;
        }
        if (IsPaused)
        {
            IsRecording = false;
            NowLabel = Loc.S("s.dash_now_idle");
            NowLabelBrush = new SolidColorBrush(Color.Parse("#FFC85C"));
            NowIcon = "⏸";
            NowTitle = Loc.S("s.status_paused");
            NowLine = Loc.S("s.dash_paused_now");
            return;
        }

        var active = _host.Tracker.ActiveSession;
        if (active is not null)
        {
            var (icon, name) = ActivityDisplay(active.ActivityKey);
            IsRecording = true;
            NowLabel = Loc.S("s.dash_now");
            NowLabelBrush = LiveBrush;
            NowIcon = icon;
            NowTitle = name;
            NowLine = $"{active.ProgramName} · {Loc.F("s.row_since", UiFormat.Clock(active.StartAtMs))} · {UiFormat.Duration(now - active.StartAtMs)}";
        }
        else
        {
            IsRecording = false;
            NowLabel = Loc.S("s.dash_now_idle");
            NowLabelBrush = IdleBrush;
            var fg = _host.Tracker.LastForeground;
            if (fg is not null)
            {
                NowIcon = "🪟";
                NowTitle = Loc.S("s.dash_ready");
                NowLine = Loc.F("s.dash_open_unmapped", fg.DisplayName);
            }
            else
            {
                NowIcon = "🖥";
                NowTitle = "—";
                NowLine = Loc.S("s.dash_no_window");
            }
        }
    }

    [ObservableProperty] private bool _probeAvailable = true;
    [ObservableProperty] private bool _isPaused;

    private static readonly IBrush LiveBrush = new SolidColorBrush(Color.Parse("#5EEAD4"));
    private static readonly IBrush IdleBrush = new SolidColorBrush(Color.Parse("#9491A5"));

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
            var (icon, name) = ActivityDisplay(key);
            var width = Math.Max(6, BarMaxWidth * ms / maxMs);
            TopBars.Add(new BarRowViewModel(icon, name, UiFormat.Duration(ms), width));
        }

        // Letzte Aufzeichnungen (laufende zuerst, dann die neuesten abgeschlossenen).
        var syncedLookup = _host.Store.GetSyncedActivities()
            .ToDictionary(a => a.Key, StringComparer.OrdinalIgnoreCase);
        Recent.Clear();
        if (active is not null)
        {
            Recent.Add(SessionRowViewModel.From(active, now, syncedLookup));
        }
        foreach (var s in completed.Take(5))
        {
            Recent.Add(SessionRowViewModel.From(s, now, syncedLookup));
        }
        HasRecent = Recent.Count > 0;
    }

    [ObservableProperty] private bool _hasRecent;

    private void UpdatePhone()
    {
        var server = _host.Server;
        var now = _host.Clock.NowMs;

        if (server.IsRunning)
        {
            var addresses = NetworkInfo.LocalIPv4Addresses();
            var host = addresses.Count > 0 ? addresses[0] : "127.0.0.1";
            PairingHintText = Loc.F("s.dash_reachable", host, server.BoundPort);
        }
        else
        {
            PairingHintText = _host.ServerError is { Length: > 0 } error
                ? Loc.F("s.dash_server_error", error)
                : Loc.S("s.dash_server_starting");
        }

        var last = server.LastClientAccessMs;
        var connected = last > 0 && now - last < 10 * 60_000L;
        IsPhoneConnected = connected;
        if (last <= 0)
        {
            PhoneStatusText = Loc.S("s.dash_phone_none");
            PhoneDetailText = "";
            PhoneDotBrush = new SolidColorBrush(Color.Parse("#9491A5"));
        }
        else
        {
            var name = server.LastClientName;
            PhoneStatusText = Loc.S("s.dash_phone_connected")
                .Replace("{0}", name.Length > 0 ? name : "…");
            PhoneDetailText = Loc.F("s.dash_phone_detail", UiFormat.Ago(last, now));
            PhoneDotBrush = new SolidColorBrush(Color.Parse(connected ? "#5EEAD4" : "#9491A5"));
        }
    }

    [RelayCommand]
    private void OpenPairing() => RequestNavigation?.Invoke("pairing");
}
