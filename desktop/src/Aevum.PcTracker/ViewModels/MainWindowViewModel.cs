using System.Collections.ObjectModel;
using Avalonia.Media;
using Avalonia.Threading;
using Aevum.PcTracker.Services;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;

namespace Aevum.PcTracker.ViewModels;

/// <summary>Seiten, die beim Öffnen frische Daten ziehen.</summary>
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
    private string _statusText = "Wird gestartet…";

    [ObservableProperty]
    private IBrush _statusDotBrush = new SolidColorBrush(Color.Parse("#9491A5"));

    [ObservableProperty]
    private string _pauseButtonText = "Pausieren";

    [ObservableProperty]
    private bool _isPaused;

    public string VersionText => $"Version {Host.Version}";

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
            new("dashboard", "Dashboard", Dashboard, this),
            new("rules", "Regeln", Rules, this),
            new("history", "Verlauf", History, this),
            new("pairing", "Kopplung", Pairing, this),
            new("settings", "Einstellungen", Settings, this),
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

        // Live-Anzeige: jeder Tracker-Takt und jede Einstellungs-Änderung
        // aktualisiert die Statuszeile (im UI-Thread).
        host.Tracker.StateChanged += RefreshStatus;
        host.Settings.Changed += RefreshStatus;
        RefreshStatus();
    }

    public void Navigate(NavItemViewModel item)
    {
        foreach (var nav in NavItems)
        {
            nav.IsSelected = ReferenceEquals(nav, item);
        }
        CurrentPage = item.Page;
        (item.Page as IRefreshable)?.Refresh();
    }

    /// <summary>Wird gerufen, sobald der Host hochgefahren ist (Server/Discovery stehen).</summary>
    public void OnHostStarted()
    {
        RefreshStatus();
        Pairing.OnHostStarted();
    }

    private void RefreshStatus() => Dispatcher.UIThread.Post(() =>
    {
        IsPaused = Host.Settings.Current.TrackingPaused;
        var probeOk = Host.Tracker.ProbeAvailable;
        StatusText = IsPaused
            ? "Aufzeichnung pausiert"
            : probeOk
                ? "Aufzeichnung aktiv"
                : "Aufzeichnung inaktiv (nur unter Windows)";
        StatusDotBrush = new SolidColorBrush(Color.Parse(
            IsPaused ? "#FFC85C" : probeOk ? "#5EEAD4" : "#9491A5"));
        PauseButtonText = IsPaused ? "Fortsetzen" : "Pausieren";
        Dashboard.Refresh();
    });

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
    public string Title { get; }
    public object Page { get; }
    public IRelayCommand SelectCommand { get; }

    [ObservableProperty]
    private bool _isSelected;

    /// <summary>Punkt links am Eintrag: Lavendel wenn aktiv, sonst Outline-Farbe.</summary>
    public IBrush IndicatorBrush => new SolidColorBrush(Color.Parse(IsSelected ? "#A99CFF" : "#3B4057"));

    public NavItemViewModel(string key, string title, object page, MainWindowViewModel owner)
    {
        Key = key;
        Title = title;
        Page = page;
        SelectCommand = new RelayCommand(() => owner.Navigate(this));
    }

    partial void OnIsSelectedChanged(bool value) => OnPropertyChanged(nameof(IndicatorBrush));
}
