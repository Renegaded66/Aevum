using Avalonia.Media;
using Avalonia.Threading;
using Aevum.PcTracker.Localization;
using Aevum.PcTracker.Services;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;

namespace Aevum.PcTracker.ViewModels;

/// <summary>Seiten, die beim Öffnen (und nach Ereignissen) frische Daten ziehen.</summary>
public interface IRefreshable
{
    void Refresh();
}

public partial class MainWindowViewModel : ObservableObject
{
    public AppHost Host { get; }
    public IReadOnlyList<NavItemViewModel> NavItems { get; }

    public DashboardViewModel Dashboard { get; }
    public RulesViewModel Rules { get; }
    public HistoryViewModel History { get; }
    public PairingViewModel Pairing { get; }
    public SettingsViewModel Settings { get; }

    [ObservableProperty]
    private object _currentPage = null!;

    [ObservableProperty]
    private string _statusText = "";

    [ObservableProperty]
    private IBrush _statusDotBrush = new SolidColorBrush(Color.Parse("#9491A5"));

    [ObservableProperty]
    private string _pauseButtonText = "";

    [ObservableProperty]
    private bool _isPaused;

    [ObservableProperty]
    private string _syncStatusText = "";

    [ObservableProperty]
    private string _syncPhoneText = "";

    [ObservableProperty]
    private bool _isPhoneConnected;

    [ObservableProperty]
    private IBrush _syncDotBrush = new SolidColorBrush(Color.Parse("#9491A5"));

    /// <summary>Flaggen-Knopf: zeigt die AKTIVE Sprache; Klick wechselt.</summary>
    public string LanguageLabel => Loc.Current == "de" ? "DE" : "EN";

    /// <summary>True = gerade Deutsch (steuert die gezeichnete Deutschland-Flagge).</summary>
    public bool IsGerman => Loc.Current == "de";

    public string VersionText => $"v{Host.Version}";

    private readonly DispatcherTimer _syncTimer;

    public MainWindowViewModel(AppHost host)
    {
        Host = host;
        Dashboard = new DashboardViewModel(host);
        Rules = new RulesViewModel(host);
        History = new HistoryViewModel(host);
        Pairing = new PairingViewModel(host);
        Settings = new SettingsViewModel(host);

        NavItems = new List<NavItemViewModel>
        {
            new("dashboard", "s.nav_dashboard", Dashboard, this),
            new("rules", "s.nav_rules", Rules, this),
            new("history", "s.nav_history", History, this),
            new("pairing", "s.nav_pairing", Pairing, this),
            new("settings", "s.nav_settings", Settings, this),
        };
        _currentPage = Dashboard;
        NavItems[0].IsSelected = true;

        // „Kopplung einrichten" auf dem Dashboard springt zum Kopplungs-Tab.
        Dashboard.RequestNavigation = key =>
        {
            var item = NavItems.FirstOrDefault(n => n.Key == key);
            if (item is not null)
            {
                Navigate(item);
            }
        };

        // Live-Anzeige: Tracker-Takt, Einstellungs-Änderungen und Zugriffe des
        // Handys aktualisieren die Oberfläche (im UI-Thread).
        host.Tracker.StateChanged += RefreshStatus;
        host.Settings.Changed += RefreshStatus;
        host.Server.ClientAccessed += OnClientAccessed;
        Loc.LanguageChanged += OnLanguageChanged;

        // „Update verfügbar“: Punkt am Einstellungen-Eintrag in der Navigation.
        Settings.UpdateBadgeChanged += has => Dispatcher.UIThread.Post(() =>
        {
            var item = NavItems.FirstOrDefault(n => n.Key == "settings");
            if (item is not null)
            {
                item.HasBadge = has;
            }
        });

        // Sync-Bar: Zeitangaben altern auch ohne Ereignisse — leicht nachziehen.
        _syncTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(5) };
        _syncTimer.Tick += (_, _) => UpdateSyncUi();
        _syncTimer.Start();

        RefreshStatus();
        UpdateSyncUi();
    }

    public void Navigate(NavItemViewModel item)
    {
        foreach (var nav in NavItems)
        {
            nav.IsSelected = ReferenceEquals(nav, item);
        }
        CurrentPage = item.Page;
        (item.Page as IRefreshable)?.Refresh();
        UpdateSyncUi();
    }

    /// <summary>Wird gerufen, sobald das Handy Daten abholt (oder Aktivitäten schickt).</summary>
    private void OnClientAccessed() => Dispatcher.UIThread.Post(() =>
    {
        UpdateSyncUi();
        (CurrentPage as IRefreshable)?.Refresh();
    });

    private void OnLanguageChanged() => Dispatcher.UIThread.Post(() =>
    {
        OnPropertyChanged(nameof(LanguageLabel));
        OnPropertyChanged(nameof(IsGerman));
        foreach (var nav in NavItems)
        {
            nav.NotifyTitleChanged();
        }
        RefreshStatus();
        UpdateSyncUi();
        (CurrentPage as IRefreshable)?.Refresh();
    });

    /// <summary>Wird gerufen, sobald der Host hochgefahren ist (Server/Discovery stehen).</summary>
    public void OnHostStarted()
    {
        RefreshStatus();
        UpdateSyncUi();
        Pairing.OnHostStarted();
        // Stiller Update-Check gegen die öffentlichen GitHub-Releases (ohne Token);
        // Fehler bleiben unsichtbar — nur der manuelle Knopf meldet Probleme.
        _ = Settings.CheckForUpdatesAsync(silent: true);
    }

    private void RefreshStatus() => Dispatcher.UIThread.Post(() =>
    {
        IsPaused = Host.Settings.Current.TrackingPaused;
        var probeOk = Host.Tracker.ProbeAvailable;
        StatusText = IsPaused
            ? Loc.S("s.status_paused")
            : probeOk
                ? Loc.S("s.status_active")
                : Loc.S("s.status_inactive");
        StatusDotBrush = new SolidColorBrush(Color.Parse(
            IsPaused ? "#FFC85C" : probeOk ? "#5EEAD4" : "#9491A5"));
        PauseButtonText = IsPaused ? Loc.S("s.resume") : Loc.S("s.pause");
        Dashboard.Refresh();
    });

    private void UpdateSyncUi()
    {
        var last = Host.Server.LastClientAccessMs;
        var now = Host.Clock.NowMs;
        var connected = last > 0 && now - last < 10 * 60_000L;
        IsPhoneConnected = connected;

        if (last <= 0)
        {
            SyncStatusText = Loc.S("s.sync_never");
        }
        else
        {
            var d = now - last;
            var relative = d < 60_000
                ? Loc.S("s.sync_just_now")
                : d < 3_600_000
                    ? Loc.F("s.sync_min_ago", (int)(d / 60_000))
                    : Loc.F("s.sync_at", UiFormat.Clock(last));
            SyncStatusText = $"{Loc.S("s.sync_last")}: {relative}";
        }

        var name = Host.Server.LastClientName;
        SyncPhoneText = connected
            ? Loc.S("s.sync_connected") + (name.Length > 0 ? $" · {name}" : "")
            : Loc.S("s.sync_not_connected");
        SyncDotBrush = new SolidColorBrush(Color.Parse(connected ? "#5EEAD4" : "#9491A5"));
    }

    [RelayCommand]
    private void RefreshCurrent()
    {
        UpdateSyncUi();
        (CurrentPage as IRefreshable)?.Refresh();
    }

    [RelayCommand]
    private void ToggleLanguage()
    {
        var next = Loc.Current == "de" ? "en" : "de";
        Host.Settings.Update(s => s.Language = next);
        Loc.SetLanguage(next);
    }

    [RelayCommand]
    private void TogglePause()
    {
        if (IsPaused)
        {
            Host.Tracker.RequestResume();
        }
        else
        {
            Host.Tracker.RequestPause();
        }
    }
}

public partial class NavItemViewModel : ObservableObject
{
    public string Key { get; }
    public string TitleKey { get; }
    public object Page { get; }
    public IRelayCommand SelectCommand { get; }

    public string Title => Loc.S(TitleKey);

    [ObservableProperty]
    private bool _isSelected;

    /// <summary>Kleiner Hinweis-Punkt rechts am Eintrag (z. B. „Update verfügbar“).</summary>
    [ObservableProperty]
    private bool _hasBadge;

    public string BadgeTooltip => Loc.S("s.upd_badge");

    /// <summary>Punkt links am Eintrag: Lavendel wenn aktiv, sonst Outline-Farbe.</summary>
    public IBrush IndicatorBrush => new SolidColorBrush(Color.Parse(IsSelected ? "#A99CFF" : "#3B4057"));

    public NavItemViewModel(string key, string titleKey, object page, MainWindowViewModel owner)
    {
        Key = key;
        TitleKey = titleKey;
        Page = page;
        SelectCommand = new RelayCommand(() => owner.Navigate(this));
    }

    public void NotifyTitleChanged()
    {
        OnPropertyChanged(nameof(Title));
        OnPropertyChanged(nameof(BadgeTooltip));
    }

    partial void OnIsSelectedChanged(bool value) => OnPropertyChanged(nameof(IndicatorBrush));
}
