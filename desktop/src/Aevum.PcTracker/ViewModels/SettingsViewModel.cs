using System.Collections.ObjectModel;
using System.Diagnostics;
using Aevum.PcTracker.Core.Models;
using Aevum.PcTracker.Core.Services;
using Aevum.PcTracker.Localization;
using Aevum.PcTracker.Services;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;

namespace Aevum.PcTracker.ViewModels;

public sealed class IdleMinuteOptionViewModel
{
    public int Minutes { get; }
    public string Label => Loc.F("s.idle_min", Minutes);

    public IdleMinuteOptionViewModel(int minutes) => Minutes = minutes;
}

public sealed class RetentionOptionViewModel
{
    public int Days { get; }
    public string Label => Days switch
    {
        365 => Loc.S("s.ret_1y"),
        730 => Loc.S("s.ret_2y"),
        _ => Loc.F("s.ret_days", Days),
    };

    public RetentionOptionViewModel(int days) => Days = days;
}

public partial class SettingsViewModel : ObservableObject, IRefreshable
{
    private const long DayMs = 86_400_000;

    private readonly AppHost _host;
    private bool _loading;

    [ObservableProperty] private bool _idleEnabled;
    [ObservableProperty] private bool _autostart;
    [ObservableProperty] private bool _autostartSupported = AutostartService.IsSupported;
    [ObservableProperty] private bool _startMinimized;
    [ObservableProperty] private bool _clearConfirmVisible;
    [ObservableProperty] private string _clearHint = "";
    [ObservableProperty] private string _versionText = "";
    [ObservableProperty] private string _dataFolderText = "";

    // --- Updates ---
    [ObservableProperty] private string _updateCurrentText = "";
    [ObservableProperty] private string _updateStatus = "";
    [ObservableProperty] private string _updateDetail = "";
    [ObservableProperty] private bool _updateNotesVisible;
    [ObservableProperty] private bool _updateDownloadVisible;
    [ObservableProperty] private bool _updateManualVisible;
    [ObservableProperty] private bool _updateBusy;
    [ObservableProperty] private bool _updateProgressVisible;
    [ObservableProperty] private double _updateProgress;

    private AvailableUpdate? _pendingUpdate;
    private string _updState = "";

    /// <summary>Der Navigations-Badge („Update verfügbar“) hört hierauf.</summary>
    public event Action<bool>? UpdateBadgeChanged;

    /// <summary>Beim Start (still) und auf Knopfdruck: öffentliche GitHub-Releases prüfen — ohne Token.</summary>
    public async Task CheckForUpdatesAsync(bool silent)
    {
        if (UpdateBusy)
        {
            return;
        }
        UpdateBusy = true;
        if (!silent)
        {
            _updState = "checking";
            RenderUpdateStatus();
        }

        var result = await _host.Updates.CheckAsync(ParseVersion(_host.Version));
        UpdateBusy = false;

        if (result.Update is not null)
        {
            _pendingUpdate = result.Update;
            UpdateDetail = result.Update.Notes.Trim();
            _updState = "available";
            UpdateBadgeChanged?.Invoke(true);
        }
        else
        {
            _pendingUpdate = null;
            if (result.Failed)
            {
                // Beim stillen Start-Check nichts anzeigen; beim manuellen Klick ehrlich melden.
                _updState = silent ? "" : "failed";
                // Badge-Zustand bei Netzfehlern unverändert lassen.
            }
            else
            {
                _updState = "uptodate";
                UpdateBadgeChanged?.Invoke(false);
            }
        }
        RenderUpdateStatus();
    }

    /// <summary>Der Knopf in den Einstellungen (mit sichtbarem Ergebnis).</summary>
    [RelayCommand]
    private async Task CheckUpdates() => await CheckForUpdatesAsync(silent: false);

    /// <summary>Setzt Statuszeile + Sichtbarkeiten aus dem Zustand — auch nach Sprachwechsel korrekt.</summary>
    private void RenderUpdateStatus()
    {
        UpdateStatus = _updState switch
        {
            "checking" => Loc.S("s.upd_checking"),
            "uptodate" => Loc.S("s.upd_uptodate"),
            "failed" => Loc.S("s.upd_error"),
            "available" => _pendingUpdate is null
                ? ""
                : Loc.F("s.upd_available", _pendingUpdate.Version),
            "downloading" => Loc.F("s.upd_downloading", (int)Math.Round(UpdateProgress)),
            "installing" => Loc.S("s.upd_installing"),
            "manual" => Loc.S("s.upd_manual"),
            _ => "",
        };
        UpdateDownloadVisible = _updState == "available";
        UpdateManualVisible = _updState == "manual";
        UpdateNotesVisible = _pendingUpdate is not null && UpdateDetail.Trim().Length > 0
            && _updState == "available";
        UpdateProgressVisible = _updState == "downloading";
    }

    // ---------------------------------------------------------------- 1.5.1:
    // Das Update-Tagebuch. Beim Start wird geprüft, ob ein gemerkter
    // Update-Auftrag (Marker aus ApplyAndRestart) durchgekommen ist. Wenn
    // nicht, sagen die Einstellungen das deutlich — statt still wieder in der
    // alten Version zu stehen (Devon: „nach ein paar sekunden hat sich einfach
    // wieder 1.4.1 geöffnet. Im ordner … keine neuen dateien").
    [ObservableProperty] private bool _updateFailedVisible;
    [ObservableProperty] private string _updateFailedText = "";

    private void CheckPendingUpdateOutcome()
    {
        var pending = PendingUpdateStore.Read(_host.DataDirectory);
        switch (PendingUpdateStore.Classify(pending, ParseVersion(_host.Version)))
        {
            case PendingUpdateOutcome.Succeeded:
                UpdateLog.Append(_host.DataDirectory,
                    $"update: erfolgreich — es läuft jetzt v{_host.Version}");
                PendingUpdateStore.Clear(_host.DataDirectory);
                UpdateFailedVisible = false;
                break;
            case PendingUpdateOutcome.Failed when pending is not null:
                UpdateFailedVisible = true;
                UpdateFailedText = Loc.F("s.upd_failed_body", pending.Target, _host.Version);
                break;
            default:
                UpdateFailedVisible = false;
                break;
        }
    }

    /// <summary>„Verstanden": Fehlermeldung ausblenden (Marker aufräumen).</summary>
    [RelayCommand]
    private void DismissUpdateFailed()
    {
        PendingUpdateStore.Clear(_host.DataDirectory);
        UpdateFailedVisible = false;
    }

    private static Version ParseVersion(string text)
        => Version.TryParse(text, out var v) ? v : new Version(1, 0, 0);

    /// <summary>Lädt das ZIP und spielt das Update ein; die App startet dabei neu.</summary>
    [RelayCommand]
    private async Task DownloadUpdateAsync()
    {
        if (_pendingUpdate is null || UpdateBusy)
        {
            return;
        }
        var appDir = AppContext.BaseDirectory;
        var exeName = Path.GetFileName(Environment.ProcessPath) is { Length: > 0 } name
            ? name
            : "AevumPcTracker.exe";

        if (!OperatingSystem.IsWindows() || !UpdateInstaller.CanWriteAppDirectory(appDir))
        {
            // Kein Schreibrecht am Programmordner (z. B. unter Programme/) —
            // ehrlich bleiben statt halbherzig kopieren; Release-Seite verlinken.
            _updState = "manual";
            RenderUpdateStatus();
            return;
        }

        UpdateBusy = true;
        _updState = "downloading";
        UpdateProgress = 0;
        RenderUpdateStatus();
        try
        {
            var zipPath = Path.Combine(Path.GetTempPath(), $"AevumPcTracker-{_pendingUpdate.Version}.zip");
            var progress = new Progress<double>(p =>
            {
                UpdateProgress = p * 100;
                RenderUpdateStatus();
            });
            await _host.Installer.DownloadAsync(_pendingUpdate, zipPath, progress, CancellationToken.None);
            _updState = "installing";
            UpdateProgressVisible = false;
            RenderUpdateStatus();
            // PC 1.5.1: Marker + Log schreiben, dann austauschen und HARt
            // beenden — das Skript wartet auf das Prozessende, kopiert mit
            // Verifikation (inkl. Fallback) und startet die App neu.
            UpdateInstaller.ApplyAndRestart(zipPath, appDir, exeName, _host.DataDirectory, _pendingUpdate.Version);
            await Task.Delay(500);
            (Avalonia.Application.Current as App)?.ShutdownForUpdate();
            // Fallback, falls das Cast unerwartet fehlschlägt (z. B. Tests):
            Environment.Exit(0);
        }
        catch (Exception)
        {
            UpdateBusy = false;
            _updState = "failed";
            RenderUpdateStatus();
        }
    }

    [RelayCommand]
    private void OpenReleasePage()
    {
        var url = _pendingUpdate?.HtmlUrl ?? "https://github.com/Renegaded66/Aevum/releases";
        try
        {
            Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
        }
        catch
        {
            // Browser ließ sich nicht öffnen — die Adresse steht in den Notes.
        }
    }

    [ObservableProperty] private IdleMinuteOptionViewModel? _selectedIdleOption;
    [ObservableProperty] private ThresholdOptionViewModel? _selectedThreshold;
    [ObservableProperty] private RetentionOptionViewModel? _selectedRetention;

    public ObservableCollection<IdleMinuteOptionViewModel> IdleOptions { get; } = new();
    public ObservableCollection<ThresholdOptionViewModel> ThresholdOptions { get; } = new();
    public ObservableCollection<RetentionOptionViewModel> RetentionOptions { get; } = new();

    public SettingsViewModel(AppHost host)
    {
        _host = host;
        BuildOptions();
        Loc.LanguageChanged += () => Avalonia.Threading.Dispatcher.UIThread.Post(() =>
        {
            // Optionen neu erzeugen, damit die Labels die neue Sprache tragen.
            BuildOptions();
            LoadFromSettings();
        });
        Refresh();
        // PC 1.5.1: Liegt ein gemerkter Update-Auftrag vor? Dann ehrlich
        // bilanzieren (erfolgreich aufgeräumt / Fehlschlag sichtbar melden).
        CheckPendingUpdateOutcome();
    }

    private void BuildOptions()
    {
        IdleOptions.Clear();
        foreach (var minutes in new[] { 5, 10, 15, 20, 30, 45, 60 })
        {
            IdleOptions.Add(new IdleMinuteOptionViewModel(minutes));
        }
        ThresholdOptions.Clear();
        foreach (var seconds in Core.Models.ThresholdOptions.Seconds)
        {
            ThresholdOptions.Add(new ThresholdOptionViewModel(seconds));
        }
        RetentionOptions.Clear();
        foreach (var days in new[] { 30, 90, 180, 365, 730 })
        {
            RetentionOptions.Add(new RetentionOptionViewModel(days));
        }
    }

    public void Refresh() => LoadFromSettings();

    private void LoadFromSettings()
    {
        _loading = true;
        var s = _host.Settings.Current;
        IdleEnabled = s.IdleEnabled;
        SelectedIdleOption = IdleOptions
            .OrderBy(o => Math.Abs(o.Minutes * 60L - s.IdleThresholdSeconds))
            .First();
        SelectedThreshold = ThresholdOptions
            .OrderBy(o => Math.Abs(o.Seconds - s.DefaultThresholdSeconds))
            .First();
        SelectedRetention = RetentionOptions
            .OrderBy(o => Math.Abs(o.Days - s.RetentionDays))
            .First();
        AutostartSupported = OperatingSystem.IsWindows();
        Autostart = OperatingSystem.IsWindows() && AutostartService.IsEnabled();
        StartMinimized = s.StartMinimized;
        VersionText = $"Aevum PC-Tracker {_host.Version}";
        DataFolderText = _host.DataDirectory;
        UpdateCurrentText = Loc.F("s.upd_current", _host.Version);
        RenderUpdateStatus();
        _loading = false;
    }

    partial void OnIdleEnabledChanged(bool value) => Save(s => s.IdleEnabled = value);

    partial void OnSelectedIdleOptionChanged(IdleMinuteOptionViewModel? value)
    {
        if (value is not null)
        {
            Save(s => s.IdleThresholdSeconds = value.Minutes * 60);
        }
    }

    partial void OnSelectedThresholdChanged(ThresholdOptionViewModel? value)
    {
        if (value is not null)
        {
            Save(s => s.DefaultThresholdSeconds = value.Seconds);
        }
    }

    partial void OnSelectedRetentionChanged(RetentionOptionViewModel? value)
    {
        if (value is null || _loading)
        {
            return;
        }
        _host.Settings.Update(s => s.RetentionDays = value.Days);
        _host.Store.PurgeSessionsBefore(_host.Clock.NowMs - value.Days * DayMs);
    }

    partial void OnStartMinimizedChanged(bool value) => Save(s => s.StartMinimized = value);

    partial void OnAutostartChanged(bool value)
    {
        if (_loading || !AutostartSupported)
        {
            return;
        }
        if (AutostartService.SetEnabled(value))
        {
            Save(s => s.Autostart = value);
        }
        else
        {
            // Registry nicht beschreibbar? Schalter zurückstellen, ehrlich bleiben.
            _loading = true;
            Autostart = AutostartService.IsEnabled();
            _loading = false;
        }
    }

    private void Save(Action<AppSettings> mutate)
    {
        if (_loading)
        {
            return;
        }
        _host.Settings.Update(mutate);
    }

    [RelayCommand]
    private void ClearHistory()
    {
        if (!ClearConfirmVisible)
        {
            ClearConfirmVisible = true;
            ClearHint = Loc.S("s.set_delete_confirm");
            return;
        }
        var deleted = _host.Store.DeleteAllSessions();
        ClearConfirmVisible = false;
        ClearHint = Loc.F("s.set_deleted", deleted == 1 ? Loc.S("s.rec_1") : Loc.F("s.rec_n", deleted));
    }

    [RelayCommand]
    private void CancelClear()
    {
        ClearConfirmVisible = false;
        ClearHint = "";
    }

    [RelayCommand]
    private void OpenDataFolder()
    {
        try
        {
            Process.Start(new ProcessStartInfo(_host.DataDirectory) { UseShellExecute = true });
        }
        catch
        {
            // Ordner lässt sich nicht öffnen — der Pfad steht als Text daneben.
        }
    }
}
