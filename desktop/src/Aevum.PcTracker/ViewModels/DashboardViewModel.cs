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

/// <summary>Eine wählbare Aktivität der allgemeinen Aufzeichnung (vom Handy gespiegelt).</summary>
public sealed class GeneralActivityOption
{
    public string Key { get; }
    public string Label { get; }

    public GeneralActivityOption(string key, string label)
    {
        Key = key;
        Label = label;
    }
}

/// <summary>
/// PC 1.3.0: eine startbare Aktivität im Dashboard. Die Liste kommt vom Handy
/// („Die möglichen Activitys sollten vom handy synchronisiert sein"), ohne
/// jemals erfolgten Sync fällt sie auf den lokalen Katalog zurück. Jeder Chip
/// trägt seinen eigenen Command (CommunityToolkit-Muster) — kein Parent-Binding,
/// keine CodeBehind-Handler.
/// </summary>
public sealed class StartActivityOption
{
    public string Key { get; }
    public string Name { get; }
    public string Label { get; }
    public System.Windows.Input.ICommand SelectCommand { get; }

    public StartActivityOption(string key, string name, string label, System.Windows.Input.ICommand selectCommand)
    {
        Key = key;
        Name = name;
        Label = label;
        SelectCommand = selectCommand;
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

    // --- Allgemeine Aufzeichnung («General») ---
    [ObservableProperty] private bool _generalEnabled;
    [ObservableProperty] private GeneralActivityOption? _selectedGeneralActivity;
    [ObservableProperty] private bool _hasGeneralActivities;
    public ObservableCollection<GeneralActivityOption> GeneralActivities { get; } = new();

    private bool _loadingGeneral;

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
        LoadGeneral();
        LoadStartActivities();
    }

    // ------------------------------------------------- Allgemeine Aufzeichnung

    /// <summary>Lädt Toggle + Aktivitätsliste aus den Einstellungen bzw. dem Handy-Sync.</summary>
    private void LoadGeneral()
    {
        _loadingGeneral = true;
        var s = _host.Settings.Current;
        GeneralEnabled = s.GeneralEnabled;

        var synced = _host.Store.GetSyncedActivities();
        HasGeneralActivities = synced.Count > 0;

        // Liste nur neu aufbauen, wenn sie sich wirklich geändert hat — sie wird
        // bei jedem UI-Refresh durchlaufen, und ein Neuaufbau würde ein gerade
        // offenes Dropdown zuklappen.
        var currentKeys = GeneralActivities.Select(o => o.Key).ToList();
        var syncedKeys = synced.Select(a => a.Key).ToList();
        if (!currentKeys.SequenceEqual(syncedKeys, StringComparer.OrdinalIgnoreCase))
        {
            GeneralActivities.Clear();
            foreach (var a in synced)
            {
                GeneralActivities.Add(new GeneralActivityOption(
                    a.Key, string.IsNullOrWhiteSpace(a.Icon) ? a.Name : $"{a.Icon}  {a.Name}"));
            }
        }

        // Auswahl nur setzen, wenn sie wirklich abweicht (sonst zappelt die ComboBox).
        if (!string.Equals(SelectedGeneralActivity?.Key, s.GeneralActivityKey, StringComparison.OrdinalIgnoreCase))
        {
            SelectedGeneralActivity = GeneralActivities
                .FirstOrDefault(o => string.Equals(o.Key, s.GeneralActivityKey, StringComparison.OrdinalIgnoreCase));
        }
        _loadingGeneral = false;
    }

    /// <summary>Übernimmt die ComboBox-Auswahl explizit (CodeBehind-Handler, wie bei den Regeln).</summary>
    public void SetGeneralActivity(GeneralActivityOption? option) => SelectedGeneralActivity = option;

    partial void OnGeneralEnabledChanged(bool value)
    {
        if (_loadingGeneral)
        {
            return;
        }
        _host.Settings.Update(s => s.GeneralEnabled = value);
        // Erster Start ohne Auswahl: die erste (alphabetische) Aktivität wählen —
        // sonst bliebe der Schalter wirkungslos. Änderbar jederzeit im Dropdown.
        if (value && SelectedGeneralActivity is null && GeneralActivities.Count > 0)
        {
            SelectedGeneralActivity = GeneralActivities[0];
        }
    }

    partial void OnSelectedGeneralActivityChanged(GeneralActivityOption? value)
    {
        if (_loadingGeneral || value is null)
        {
            return;
        }
        _host.Settings.Update(s => s.GeneralActivityKey = value.Key);
    }

    // ------------------------------------------------ Aktivität starten (M20)

    /// <summary>Alle startbaren Aktivitäten (vom Handy gespiegelt, sonst Katalog).</summary>
    public ObservableCollection<StartActivityOption> StartActivities { get; } = new();

    [ObservableProperty] private StartActivityOption? _selectedStartActivity;
    [ObservableProperty] private bool _hasSelectedStartActivity;

    /// <summary>Läuft gerade eine MANUELL gestartete Aktivität? (steuert den Stop-Knopf)</summary>
    [ObservableProperty] private bool _isManualActive;

    /// <summary>Start-Karte bedienbar? (bei globaler Pause nein — erst fortsetzen)</summary>
    [ObservableProperty] private bool _isStartEnabled = true;

    // Vorlaufzeit („+5/15/30 Min"): Der Countdown lebt nur im UI (Sekunden-Timer);
    // beim Ablauf startet der Tracker. Ein App-Neustart verwirft einen laufenden
    // Countdown bewusst — eine halb gestartete Aktivität wäre schlimmer als keine.
    [ObservableProperty] private bool _hasPendingStart;
    [ObservableProperty] private string _pendingStartText = "";
    private string? _pendingStartKey;
    private string _pendingStartName = "";
    private long _pendingStartAtMs;

    /// <summary>Lädt die Chips (nur bei echter Änderung neu aufbauen — sonst flackert das Hover).</summary>
    private void LoadStartActivities()
    {
        var synced = _host.Store.GetSyncedActivities();
        var items = synced.Count > 0
            ? synced.Select(a => (a.Key, a.Name, a.Icon)).ToList()
            : ActivityCatalog.All.Select(c => (c.Key, c.Name, c.Icon)).ToList();
        var currentKeys = StartActivities.Select(o => o.Key).ToList();
        if (currentKeys.SequenceEqual(items.Select(i => i.Key), StringComparer.OrdinalIgnoreCase))
        {
            return;
        }
        StartActivities.Clear();
        foreach (var (key, name, icon) in items)
        {
            var label = string.IsNullOrWhiteSpace(icon) ? name : $"{icon}  {name}";
            StartActivities.Add(new StartActivityOption(key, name, label, SelectStartActivityCommand));
        }
        if (SelectedStartActivity is not null &&
            !items.Any(i => string.Equals(i.Key, SelectedStartActivity.Key, StringComparison.OrdinalIgnoreCase)))
        {
            SelectedStartActivity = null;
            HasSelectedStartActivity = false;
        }
    }

    [RelayCommand]
    private void SelectStartActivity(StartActivityOption? option)
    {
        if (option is null)
        {
            return;
        }
        SelectedStartActivity = option;
        HasSelectedStartActivity = true;
    }

    [RelayCommand] private void StartNow() => BeginStart(0);
    [RelayCommand] private void StartIn5() => BeginStart(5);
    [RelayCommand] private void StartIn15() => BeginStart(15);
    [RelayCommand] private void StartIn30() => BeginStart(30);

    /// <summary>Sofort (minutes = 0) oder mit Vorlaufzeit — der Countdown läuft, Abbrechen möglich.</summary>
    private void BeginStart(int minutes)
    {
        var option = SelectedStartActivity;
        if (option is null)
        {
            return;
        }
        if (minutes <= 0)
        {
            ClearPendingStart();
            _host.Tracker.StartManualActivity(option.Key);
            return; // Refresh kommt über Tracker.StateChanged
        }
        _pendingStartKey = option.Key;
        _pendingStartName = option.Name;
        _pendingStartAtMs = _host.Clock.NowMs + minutes * 60_000L;
        UpdatePendingText(_host.Clock.NowMs);
    }

    [RelayCommand]
    private void CancelPendingStart() => ClearPendingStart();

    /// <summary>Stop-Knopf: die laufende (manuelle) Session endet sofort.</summary>
    [RelayCommand]
    private void StopSession() => _host.Tracker.StopActiveSession();

    private void ClearPendingStart()
    {
        _pendingStartKey = null;
        HasPendingStart = false;
        PendingStartText = "";
    }

    /// <summary>Aus dem Sekunden-Timer: Countdown anzeigen und bei Ablauf starten.</summary>
    private void CheckPendingStart(long now)
    {
        if (_pendingStartKey is null)
        {
            return;
        }
        if (now >= _pendingStartAtMs)
        {
            var key = _pendingStartKey;
            ClearPendingStart();
            _host.Tracker.StartManualActivity(key);
            return; // Anzeige aktualisiert der StateChanged-getriebene Refresh
        }
        UpdatePendingText(now);
    }

    private void UpdatePendingText(long now)
    {
        if (_pendingStartKey is null)
        {
            return;
        }
        var remaining = Math.Max(0, _pendingStartAtMs - now);
        var clock = $"{remaining / 60_000}:{remaining % 60_000 / 1000:00}";
        PendingStartText = Loc.F("s.start_pending", _pendingStartName, clock);
        HasPendingStart = true;
    }

    /// <summary>Aktivitätsname+Icon aus Sicht der App: synchronisierte zuerst, sonst Katalog.</summary>
    private (string Icon, string Name) ActivityDisplay(string activityKey)
    {
        var synced = _host.Store.GetSyncedActivities()
            .ToDictionary(a => a.Key, StringComparer.OrdinalIgnoreCase);
        return SessionRowViewModel.ActivityDisplay(activityKey, synced);
    }

    // PC 1.4.0: Live-Zustand vom Handy (Dashboard-Karte „Auf deinem Handy").
    // Quelle ist die PhoneLiveRegistry — gespeist von POST /api/live der App
    // (alle wenigen Sekunden), gesteuert über Befehl-Antworten an die App.
    [ObservableProperty] private bool _phoneLiveVisible;
    [ObservableProperty] private bool _phoneLiveStale;
    [ObservableProperty] private string _phoneLiveIcon = "📱";
    [ObservableProperty] private string _phoneLiveTitle = "";
    [ObservableProperty] private string _phoneLiveLine = "";
    [ObservableProperty] private string _phoneLiveHint = "";
    [ObservableProperty] private string _phonePauseResumeLabel = "";

    private void UpdatePhoneLive(long now)
    {
        var status = _host.PhoneLive.GetStatus(now);
        var session = status.Session;
        PhoneLiveVisible = session is not null;
        if (session is null)
        {
            return;
        }
        PhoneLiveStale = status.Stale;
        PhoneLiveIcon = string.IsNullOrWhiteSpace(session.Icon) ? "📱" : session.Icon;
        PhoneLiveTitle = session.ActivityName;
        PhoneLiveLine = session.IsPaused
            ? Loc.F("s.plive_paused", UiFormat.Clock(session.BlockEndAtMs > 0 ? session.BlockEndAtMs : session.StartAtMs))
            : Loc.F("s.plive_running", UiFormat.Clock(session.StartAtMs), UiFormat.Duration(now - session.StartAtMs));
        PhonePauseResumeLabel = Loc.S(session.IsPaused ? "s.plive_resume" : "s.plive_pause");
        PhoneLiveHint = status.PendingCommand is not null
            ? Loc.S("s.plive_busy")
            : status.Stale
                ? Loc.F("s.plive_stale", UiFormat.Ago(session.LastUpdateMs, now))
                : Loc.S("s.plive_hint");
    }

    [RelayCommand]
    private void PhonePauseResume()
    {
        var status = _host.PhoneLive.GetStatus(_host.Clock.NowMs);
        if (status.Session is null)
        {
            return;
        }
        _host.PhoneLive.RequestCommand(
            status.Session.IsPaused ? PhoneLiveCommand.Resume : PhoneLiveCommand.Pause,
            _host.Clock.NowMs);
    }

    [RelayCommand]
    private void PhoneStop()
    {
        if (_host.PhoneLive.GetStatus(_host.Clock.NowMs).Session is null)
        {
            return;
        }
        _host.PhoneLive.RequestCommand(PhoneLiveCommand.Stop, _host.Clock.NowMs);
    }

    private void UpdateLive()
    {
        var now = _host.Clock.NowMs;
        ProbeAvailable = _host.Tracker.ProbeAvailable;
        IsPaused = _host.Settings.Current.TrackingPaused;
        IsStartEnabled = !IsPaused;
        CheckPendingStart(now);
        IsManualActive = _host.Tracker.ActiveSession?.ProgramKey == SessionEngine.ManualProgramKey;
        UpdatePhoneLive(now);

        // PC 1.3.0: Eine MANUELLE Session wird immer angezeigt — sie hängt nicht
        // an der Windows-Probe (der Linux-Smoke-Test zeigte: ohne diesen Vorrang
        // startete die Aktivität, blieb aber unsichtbar) und auch nicht an der
        // Regel-/Idle-Logik.
        var manual = _host.Tracker.ActiveSession is { ProgramKey: SessionEngine.ManualProgramKey }
            ? _host.Tracker.ActiveSession
            : null;
        if (manual is not null)
        {
            var (manualIcon, manualName) = ActivityDisplay(manual.ActivityKey);
            IsRecording = true;
            NowLabel = Loc.S("s.dash_now");
            NowLabelBrush = LiveBrush;
            NowIcon = manualIcon;
            NowTitle = manualName;
            NowLine = $"{Loc.S("s.man_started")} · {Loc.F("s.row_since", UiFormat.Clock(manual.StartAtMs))} · {UiFormat.Duration(now - manual.StartAtMs)}";
            return;
        }

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
            var subject = active.ProgramKey == SessionEngine.GeneralProgramKey
                ? Loc.S("s.gen_in_use")
                : active.ProgramName;
            NowLine = $"{subject} · {Loc.F("s.row_since", UiFormat.Clock(active.StartAtMs))} · {UiFormat.Duration(now - active.StartAtMs)}";
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
            // Stand der gespiegelten Aktivitäten zeigen — macht den regelmäßigen
            // Sync sichtbar („der pc soll regelmäßig mit dem handy synchronisieren").
            var activityCount = _host.Store.GetSyncedActivities().Count;
            var activitiesText = activityCount > 0
                ? $" · {Loc.F("s.dash_phone_activities", activityCount)}"
                : "";
            PhoneDetailText = Loc.F("s.dash_phone_detail", UiFormat.Ago(last, now)) + activitiesText;
            PhoneDotBrush = new SolidColorBrush(Color.Parse(connected ? "#5EEAD4" : "#9491A5"));
        }
    }

    [RelayCommand]
    private void OpenPairing() => RequestNavigation?.Invoke("pairing");
}
