using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Markup.Xaml;
using Aevum.PcTracker.Localization;
using Aevum.PcTracker.Services;
using Aevum.PcTracker.ViewModels;
using Aevum.PcTracker.Views;

namespace Aevum.PcTracker;

public partial class App : Application
{
    private AppHost? _host;
    private MainWindow? _mainWindow;
    private bool _shutdownStarted;

    public override void Initialize()
    {
        AvaloniaXamlLoader.Load(this);
    }

    public override void OnFrameworkInitializationCompleted()
    {
        if (ApplicationLifetime is not IClassicDesktopStyleApplicationLifetime desktop)
        {
            base.OnFrameworkInitializationCompleted();
            return;
        }

        // Beenden nur über das Tray-Menü / expliziten Shutdown — das X versteckt
        // die App (siehe MainWindow.OnClosing).
        desktop.ShutdownMode = ShutdownMode.OnExplicitShutdown;

        // Einzelinstanz: eine zweite Instanz zeigt nur einen Hinweis.
        if (!SingleInstance.TryAcquire())
        {
            desktop.MainWindow = InfoWindows.Create(
                Loc.S("s.msg_running_title"),
                Loc.S("s.msg_running_body"));
            base.OnFrameworkInitializationCompleted();
            return;
        }

        try
        {
            _host = new AppHost();
        }
        catch (Exception ex)
        {
            desktop.MainWindow = InfoWindows.Create(
                Loc.S("s.msg_startfail_title"),
                Loc.S("s.msg_startfail_body") + "\n\n" + ex.Message);
            base.OnFrameworkInitializationCompleted();
            return;
        }

        // UI-Sprache aus den Einstellungen anwenden (Default Englisch) und das
        // Sprach-Wörterbuch in die App-Ressourcen legen — vor dem Fensterbau.
        Loc.Init(_host.Settings.Current.Language);
        Loc.Apply(this);

        var viewModel = new MainWindowViewModel(_host);
        var window = new MainWindow { DataContext = viewModel };
        _mainWindow = window;
        desktop.MainWindow = window;

        SetupTray(desktop, window, viewModel);

        // Mit --minimized (Autostart) oder per Einstellung startet die App still
        // in den Infobereich.
        var startMinimized = Environment.GetCommandLineArgs().Any(a => a == "--minimized")
            || _host.Settings.Current.StartMinimized;
        if (!startMinimized)
        {
            window.Show();
        }

        // Dienste asynchron starten — die Oberfläche ist sofort da und zeigt
        // den Serverstatus, sobald er steht.
        _ = StartHostAsync(viewModel, window);

        desktop.Exit += (_, _) => ShutdownHost();
        base.OnFrameworkInitializationCompleted();
    }

    private async Task StartHostAsync(MainWindowViewModel viewModel, MainWindow window)
    {
        if (_host is null)
        {
            return;
        }
        try
        {
            await _host.StartAsync();
        }
        catch
        {
            // ServerStart meldet Fehler über ServerError — die UI zeigt sie an.
        }
        await Avalonia.Threading.Dispatcher.UIThread.InvokeAsync(() => viewModel.OnHostStarted());
    }

    private void ShutdownHost()
    {
        try
        {
            _host?.StopAsync().Wait(TimeSpan.FromSeconds(4));
        }
        catch
        {
            // Beim Beenden nichts mehr zu retten.
        }
    }

    private void SetupTray(IClassicDesktopStyleApplicationLifetime desktop, MainWindow window, MainWindowViewModel viewModel)
    {
        // Nur auf Windows: Die Tray-Anbindung ist Teil der Windows-Erfahrung
        // (Infobereich). Auf anderen Systemen läuft die App auch ohne Tray
        // vollständig — dort gibt es dann nur das normale Fenster.
        if (!OperatingSystem.IsWindows())
        {
            return;
        }

        var icon = new TrayIcon
        {
            Icon = LoadAppIcon(),
            ToolTipText = Loc.S("s.tray_tooltip"),
            IsVisible = true,
        };

        var menu = new NativeMenu();
        var openItem = new NativeMenuItem(Loc.S("s.tray_open"));
        openItem.Click += (_, _) => ShowWindow(window);
        var pauseItem = new NativeMenuItem(Loc.S("s.tray_pause"))
        {
            ToggleType = NativeMenuItemToggleType.CheckBox,
            IsChecked = viewModel.IsPaused,
        };
        pauseItem.Click += (_, _) => viewModel.TogglePauseCommand.Execute(null);
        var quitItem = new NativeMenuItem(Loc.S("s.tray_quit"));
        quitItem.Click += (_, _) => RequestShutdown(desktop);
        menu.Items.Add(openItem);
        menu.Items.Add(pauseItem);
        menu.Items.Add(new NativeMenuItemSeparator());
        menu.Items.Add(quitItem);
        icon.Menu = menu;
        icon.Clicked += (_, _) => ShowWindow(window);

        viewModel.PropertyChanged += (_, e) =>
        {
            if (e.PropertyName == nameof(MainWindowViewModel.IsPaused))
            {
                pauseItem.IsChecked = viewModel.IsPaused;
                icon.ToolTipText = Loc.S(viewModel.IsPaused ? "s.tray_tooltip_paused" : "s.tray_tooltip");
            }
        };

        // Sprachwechsel zieht die Tray-Texte nach (kommt aus dem UI-Thread).
        Loc.LanguageChanged += () =>
        {
            openItem.Header = Loc.S("s.tray_open");
            pauseItem.Header = Loc.S("s.tray_pause");
            quitItem.Header = Loc.S("s.tray_quit");
            icon.ToolTipText = Loc.S(viewModel.IsPaused ? "s.tray_tooltip_paused" : "s.tray_tooltip");
        };

        TrayIcon.SetIcons(this, new TrayIcons { icon });
    }

    private static Avalonia.Controls.WindowIcon LoadAppIcon()
    {
        using var stream = Avalonia.Platform.AssetLoader.Open(
            new Uri("avares://AevumPcTracker/Assets/aevum-256.png"));
        return new Avalonia.Controls.WindowIcon(stream);
    }

    private static void ShowWindow(MainWindow window)
    {
        window.Show();
        if (window.WindowState == Avalonia.Controls.WindowState.Minimized)
        {
            window.WindowState = Avalonia.Controls.WindowState.Normal;
        }
        window.Activate();
    }

    public void RequestShutdown(IClassicDesktopStyleApplicationLifetime desktop)
    {
        if (_shutdownStarted)
        {
            return;
        }
        _shutdownStarted = true;
        if (_mainWindow is not null)
        {
            _mainWindow.AllowClose = true;
        }
        ShutdownHost();
        desktop.Shutdown();
    }
}
