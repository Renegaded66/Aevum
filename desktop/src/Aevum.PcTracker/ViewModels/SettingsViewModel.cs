using System.Collections.ObjectModel;
using System.Diagnostics;
using Aevum.PcTracker.Core.Models;
using Aevum.PcTracker.Services;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;

namespace Aevum.PcTracker.ViewModels;

public sealed class IdleMinuteOptionViewModel
{
    public int Minutes { get; }
    public string Label { get; }

    public IdleMinuteOptionViewModel(int minutes)
    {
        Minutes = minutes;
        Label = $"{minutes} Minuten";
    }
}

public sealed class RetentionOptionViewModel
{
    public int Days { get; }
    public string Label { get; }

    public RetentionOptionViewModel(int days)
    {
        Days = days;
        Label = days switch
        {
            365 => "1 Jahr",
            730 => "2 Jahre",
            _ => $"{days} Tage",
        };
    }
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

    [ObservableProperty] private IdleMinuteOptionViewModel? _selectedIdleOption;
    [ObservableProperty] private ThresholdOptionViewModel? _selectedThreshold;
    [ObservableProperty] private RetentionOptionViewModel? _selectedRetention;

    public ObservableCollection<IdleMinuteOptionViewModel> IdleOptions { get; } = new();
    public ObservableCollection<ThresholdOptionViewModel> ThresholdOptions { get; } = new();
    public ObservableCollection<RetentionOptionViewModel> RetentionOptions { get; } = new();

    public SettingsViewModel(AppHost host)
    {
        _host = host;
        foreach (var minutes in new[] { 5, 10, 15, 20, 30, 45, 60 })
        {
            IdleOptions.Add(new IdleMinuteOptionViewModel(minutes));
        }
        foreach (var seconds in Core.Models.ThresholdOptions.Seconds)
        {
            ThresholdOptions.Add(new ThresholdOptionViewModel(seconds));
        }
        foreach (var days in new[] { 30, 90, 180, 365, 730 })
        {
            RetentionOptions.Add(new RetentionOptionViewModel(days));
        }
        Refresh();
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
            ClearHint = "Wirklich den gesamten lokalen Verlauf löschen? Das lässt sich nicht rückgängig machen.";
            return;
        }
        var deleted = _host.Store.DeleteAllSessions();
        ClearConfirmVisible = false;
        ClearHint = $"{deleted} Aufzeichnung{(deleted == 1 ? "" : "en")} gelöscht.";
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
