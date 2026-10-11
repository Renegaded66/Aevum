using System.Collections.ObjectModel;
using Aevum.PcTracker.Core.Models;
using Aevum.PcTracker.Localization;
using Aevum.PcTracker.Services;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;

namespace Aevum.PcTracker.ViewModels;

/// <summary>Ein Eintrag in der Programm-Auswahl (bekannt oder auf dem PC gesehen).</summary>
public sealed class ProgramCandidateViewModel
{
    public string ProgramKey { get; }
    public string Name { get; }
    public string Badge { get; }
    public bool HasRule { get; }
    public string SuggestionText { get; }
    public IRelayCommand SelectCommand { get; }

    public ProgramCandidateViewModel(string key, string name, string badge, bool hasRule,
        string suggestionText, RulesViewModel owner)
    {
        ProgramKey = key;
        Name = name;
        Badge = badge;
        HasRule = hasRule;
        SuggestionText = suggestionText;
        SelectCommand = new RelayCommand(() => owner.SelectProgram(key, name));
    }
}

/// <summary>Auswahloption für Aktivitäten: bevorzugt die vom Handy synchronisierten.</summary>
public sealed class ActivityOptionViewModel
{
    public string Key { get; }
    public string Name { get; }
    public string Icon { get; }
    public string CategoryKey { get; }
    public bool FromPhone { get; }
    public string Display => string.IsNullOrEmpty(Icon) ? Name : $"{Icon} {Name}";
    public string SourceLabel => Loc.S(FromPhone ? "s.rules_activity_app" : "s.rules_activity_catalog");

    public ActivityOptionViewModel(string key, string icon, string name, string categoryKey, bool fromPhone)
    {
        Key = key;
        Icon = icon;
        Name = name;
        CategoryKey = categoryKey;
        FromPhone = fromPhone;
    }
}

/// <summary>Aufnahme-Modus für die Regel-Auswahl.</summary>
public sealed class ModeOptionViewModel
{
    public RecordMode Mode { get; }
    public string Label => Loc.S(Mode == RecordMode.WhileRunning ? "s.rules_mode_bg" : "s.rules_mode_fg");

    public ModeOptionViewModel(RecordMode mode) => Mode = mode;
}

public sealed class ThresholdOptionViewModel
{
    public int Seconds { get; }
    public string Label { get; }

    public ThresholdOptionViewModel(int seconds)
    {
        Seconds = seconds;
        Label = ThresholdLabel(seconds);
    }

    /// <summary>Lokalisiertes Label („1 Minute“ / “1 minute“) — die Zahl bleibt der stabile Wert.</summary>
    public static string ThresholdLabel(int seconds)
    {
        var key = $"s.thr_{seconds}";
        var text = Loc.S(key);
        return text == key ? $"{seconds / 60} min" : text;
    }
}

/// <summary>Ein gerade offenes Programm („Jetzt geöffnet“-Liste).</summary>
public sealed class RunningProgramViewModel
{
    public string ProgramKey { get; }
    public string Name { get; }
    public string Title { get; }
    public bool HasRule { get; }
    public IRelayCommand MapCommand { get; }

    public RunningProgramViewModel(RunningProgram program, bool hasRule, RulesViewModel owner)
    {
        ProgramKey = program.ProgramKey;
        Name = program.Name;
        Title = program.Title;
        HasRule = hasRule;
        MapCommand = new RelayCommand(() => owner.SelectProgram(program.ProgramKey, program.Name));
    }
}

public partial class RuleRowViewModel : ObservableObject
{
    private readonly RulesViewModel _owner;

    public string ProgramKey { get; }
    public string ProgramName { get; }
    public string ActivityDisplay { get; }
    public string ThresholdLabel { get; }
    public string ModeLabel { get; }
    public IRelayCommand DeleteCommand { get; }
    public IRelayCommand EditCommand { get; }

    [ObservableProperty]
    private bool _isEnabled;

    public RuleRowViewModel(TrackingRule rule, string activityDisplay, RulesViewModel owner)
    {
        _owner = owner;
        ProgramKey = rule.ProgramKey;
        ProgramName = rule.ProgramName;
        ActivityDisplay = activityDisplay;
        ThresholdLabel = Loc.F("s.rules_threshold", ThresholdOptionViewModel.ThresholdLabel(rule.ThresholdSeconds));
        ModeLabel = Loc.S(rule.Mode == RecordMode.WhileRunning ? "s.rules_mode_bg" : "s.rules_mode_fg");
        _isEnabled = rule.Enabled; // direkt ins Feld: kein Toggle-Event beim Laden
        DeleteCommand = new RelayCommand(() => _owner.DeleteRule(this));
        EditCommand = new RelayCommand(() => _owner.EditRule(this));
    }

    partial void OnIsEnabledChanged(bool value) => _owner.OnRuleToggled(this, value);
}

public partial class RulesViewModel : ObservableObject, IRefreshable
{
    private readonly AppHost _host;

    public ObservableCollection<RuleRowViewModel> Rules { get; } = new();
    public ObservableCollection<ProgramCandidateViewModel> Candidates { get; } = new();
    public ObservableCollection<ActivityOptionViewModel> ActivityOptions { get; } = new();
    public ObservableCollection<ThresholdOptionViewModel> ThresholdOptions { get; } = new();
    public ObservableCollection<ModeOptionViewModel> ModeOptions { get; } = new();
    public ObservableCollection<RunningProgramViewModel> RunningPrograms { get; } = new();

    [ObservableProperty] private string _searchText = "";
    [ObservableProperty] private bool _hasRules;
    [ObservableProperty] private bool _isDraftOpen;
    [ObservableProperty] private string _draftTitle = "";
    [ObservableProperty] private string _draftSubtitle = "";
    [ObservableProperty] private ActivityOptionViewModel? _draftActivity;
    [ObservableProperty] private ThresholdOptionViewModel? _draftThreshold;
    [ObservableProperty] private ModeOptionViewModel? _draftMode;

    [ObservableProperty] private bool _hasPhoneActivities;
    [ObservableProperty] private bool _isRunningListOpen;
    [ObservableProperty] private string _customProgramText = "";

    private string _draftKey = "";
    private string _draftName = "";

    public RulesViewModel(AppHost host)
    {
        _host = host;
        foreach (var seconds in Core.Models.ThresholdOptions.Seconds)
        {
            ThresholdOptions.Add(new ThresholdOptionViewModel(seconds));
        }
        ModeOptions.Add(new ModeOptionViewModel(RecordMode.Foreground));
        ModeOptions.Add(new ModeOptionViewModel(RecordMode.WhileRunning));
        Loc.LanguageChanged += OnLanguageChanged;
        Refresh();
    }

    private void OnLanguageChanged()
    {
        Avalonia.Threading.Dispatcher.UIThread.Post(Refresh);
    }

    public void Refresh() => ReloadAll();

    partial void OnSearchTextChanged(string value) => ReloadCandidates();

    private void ReloadAll()
    {
        var activityOptions = BuildActivityOptions();
        var activeKey = DraftActivity?.Key;
        ActivityOptions.Clear();
        foreach (var option in activityOptions)
        {
            ActivityOptions.Add(option);
        }
        HasPhoneActivities = ActivityOptions.Any(a => a.FromPhone);
        // Offene Auswahl über den Key wiederfinden (Objekte sind neu).
        if (activeKey is not null)
        {
            DraftActivity = ActivityOptions.FirstOrDefault(a => a.Key == activeKey) ?? DraftActivity;
        }

        // Offenen Entwurf sprachsicher halten: Untertitel neu aus der Sprache.
        if (IsDraftOpen && _draftKey.Length > 0 && Rules.Any(r => r.ProgramKey == _draftKey))
        {
            DraftSubtitle = Loc.S("s.rules_edit_existing");
        }

        // Schwellen- und Modus-Optionen ebenfalls neu aufbauen (Sprachwechsel).
        var activeSeconds = DraftThreshold?.Seconds;
        ThresholdOptions.Clear();
        foreach (var seconds in Core.Models.ThresholdOptions.Seconds)
        {
            ThresholdOptions.Add(new ThresholdOptionViewModel(seconds));
        }
        if (activeSeconds is not null)
        {
            DraftThreshold = ThresholdOptions.FirstOrDefault(t => t.Seconds == activeSeconds) ?? DraftThreshold;
        }
        var activeMode = DraftMode?.Mode;
        ModeOptions.Clear();
        ModeOptions.Add(new ModeOptionViewModel(RecordMode.Foreground));
        ModeOptions.Add(new ModeOptionViewModel(RecordMode.WhileRunning));
        if (activeMode is not null)
        {
            DraftMode = ModeOptions.FirstOrDefault(m => m.Mode == activeMode) ?? DraftMode;
        }

        var rules = _host.Store.GetRules();
        Rules.Clear();
        foreach (var rule in rules)
        {
            Rules.Add(new RuleRowViewModel(rule, ActivityDisplayFor(rule.ActivityKey, activityOptions), this));
        }
        HasRules = Rules.Count > 0;
        ReloadCandidates();
        ReloadRunning();
    }

    /// <summary>
    /// Aktivitäten: zuerst die vom Handy synchronisierten (gleiche Namen wie in
    /// der App), darunter der lokale Katalog ohne Namens-Dubletten.
    /// </summary>
    private List<ActivityOptionViewModel> BuildActivityOptions()
    {
        var synced = _host.Store.GetSyncedActivities();
        var result = new List<ActivityOptionViewModel>(synced.Count + ActivityCatalog.All.Count);
        var names = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var s in synced)
        {
            result.Add(new ActivityOptionViewModel(s.Key, s.Icon, s.Name, s.CategoryKey, fromPhone: true));
            names.Add(s.Name);
        }
        foreach (var a in ActivityCatalog.All)
        {
            if (names.Contains(a.Name))
            {
                continue;
            }
            result.Add(new ActivityOptionViewModel(a.Key, a.Icon, a.Name, a.CategoryKey, fromPhone: false));
        }
        return result;
    }

    private string ActivityDisplayFor(string activityKey, IReadOnlyList<ActivityOptionViewModel> options)
    {
        var option = options.FirstOrDefault(o => o.Key == activityKey);
        if (option is not null)
        {
            return option.Display;
        }
        var catalog = ActivityCatalog.ByKeyOrOther(activityKey);
        return $"{catalog.Icon} {catalog.Name}";
    }

    private void ReloadCandidates()
    {
        var ruleKeys = Rules.Select(r => r.ProgramKey).ToHashSet(StringComparer.OrdinalIgnoreCase);
        var query = SearchText.Trim();
        var result = new List<ProgramCandidateViewModel>();

        // Zuerst: Programme, die auf diesem PC tatsächlich gesehen wurden.
        var seenKeys = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var program in _host.Store.GetPrograms().Where(p => !p.Hidden))
        {
            seenKeys.Add(program.ProgramKey);
            if (!Matches(program.Name, program.ProgramKey, query))
            {
                continue;
            }
            var suggestion = KnownPrograms.All.FirstOrDefault(k =>
                string.Equals(k.Exe, program.ProgramKey, StringComparison.OrdinalIgnoreCase));
            result.Add(new ProgramCandidateViewModel(
                program.ProgramKey,
                program.Name,
                Loc.S("s.rules_badge_seen"),
                ruleKeys.Contains(program.ProgramKey),
                SuggestionTextFor(suggestion?.SuggestedActivityKey),
                this));
        }

        // Danach: kuratierte Vorschläge, die noch nicht aufgetaucht sind.
        foreach (var known in KnownPrograms.All)
        {
            if (seenKeys.Contains(known.Exe) || !Matches(known.Name, known.Exe, query))
            {
                continue;
            }
            result.Add(new ProgramCandidateViewModel(
                known.Exe,
                known.Name,
                Loc.S("s.rules_badge_suggestion"),
                ruleKeys.Contains(known.Exe),
                SuggestionTextFor(known.SuggestedActivityKey),
                this));
        }

        Candidates.Clear();
        foreach (var candidate in result.Take(80))
        {
            Candidates.Add(candidate);
        }
    }

    private void ReloadRunning()
    {
        if (!IsRunningListOpen)
        {
            HasRunning = false;
            return;
        }
        var ruleKeys = Rules.Select(r => r.ProgramKey).ToHashSet(StringComparer.OrdinalIgnoreCase);
        RunningPrograms.Clear();
        try
        {
            foreach (var program in _host.Tracker.GetRunningPrograms())
            {
                RunningPrograms.Add(new RunningProgramViewModel(
                    program, ruleKeys.Contains(program.ProgramKey), this));
            }
        }
        catch
        {
            // Probe nicht verfügbar (z. B. außerhalb Windows) — Liste bleibt leer.
        }
        HasRunning = RunningPrograms.Count > 0;
    }

    [ObservableProperty] private bool _hasRunning;

    private static bool Matches(string name, string key, string query) =>
        query.Length == 0
        || name.Contains(query, StringComparison.OrdinalIgnoreCase)
        || key.Contains(query, StringComparison.OrdinalIgnoreCase);

    private static string SuggestionTextFor(string? activityKey)
    {
        var activity = ActivityCatalog.ByKey(activityKey);
        return activity is null ? "" : $"{activity.Icon} {activity.Name}";
    }

    /// <summary>Öffnet den Entwurf für ein Programm (Kandidat, laufendes Programm oder manuell).</summary>
    public void SelectProgram(string programKey, string programName)
    {
        _draftKey = programKey;
        _draftName = programName;
        DraftTitle = programName;
        var existing = _host.Store.GetRules().FirstOrDefault(r =>
            string.Equals(r.ProgramKey, programKey, StringComparison.OrdinalIgnoreCase));
        if (existing is not null)
        {
            DraftSubtitle = Loc.S("s.rules_edit_existing");
            DraftActivity = ActivityOptions.FirstOrDefault(a => a.Key == existing.ActivityKey) ?? PickDefaultActivity();
            DraftThreshold = ThresholdOptions.FirstOrDefault(t => t.Seconds == existing.ThresholdSeconds) ?? PickDefaultThreshold();
            DraftMode = ModeOptions.FirstOrDefault(m => m.Mode == existing.Mode) ?? ModeOptions[0];
        }
        else
        {
            DraftSubtitle = "";
            var suggested = KnownPrograms.All.FirstOrDefault(k =>
                string.Equals(k.Exe, programKey, StringComparison.OrdinalIgnoreCase))?.SuggestedActivityKey;
            DraftActivity = ActivityOptions.FirstOrDefault(a => a.Key == suggested) ?? PickDefaultActivity();
            DraftThreshold = PickDefaultThreshold();
            DraftMode = ModeOptions[0];
        }
        IsDraftOpen = true;
    }

    /// <summary>„Bearbeiten“ an einer bestehenden Zuordnung.</summary>
    public void EditRule(RuleRowViewModel row) => SelectProgram(row.ProgramKey, row.ProgramName);

    /// <summary>Manuell eingegebenes Programm („nicht in der Liste“) übernehmen.</summary>
    [RelayCommand]
    private void AddCustomProgram()
    {
        var raw = CustomProgramText.Trim();
        if (raw.Length == 0)
        {
            return;
        }
        var name = raw;
        var key = raw.ToLowerInvariant();
        if (!key.EndsWith(".exe", StringComparison.Ordinal))
        {
            key += ".exe";
        }
        name = name.EndsWith(".exe", StringComparison.OrdinalIgnoreCase) ? name[..^4] : name;
        CustomProgramText = "";
        SelectProgram(key, name);
    }

    /// <summary>„Jetzt geöffnete Programme“ ein-/ausblenden (frische Momentaufnahme).</summary>
    [RelayCommand]
    private void ToggleRunning()
    {
        IsRunningListOpen = !IsRunningListOpen;
        ReloadRunning();
    }

    [RelayCommand]
    private void RefreshRunning() => ReloadRunning();

    /// <summary>Explizite Übernahme aus der ComboBox (Code-behind) — unabhängig vom Binding.</summary>
    public void SetDraftActivity(ActivityOptionViewModel? option)
    {
        if (option is not null)
        {
            DraftActivity = option;
        }
    }

    /// <summary>Explizite Übernahme aus der ComboBox (Code-behind) — unabhängig vom Binding.</summary>
    public void SetDraftThreshold(ThresholdOptionViewModel? option)
    {
        if (option is not null)
        {
            DraftThreshold = option;
        }
    }

    /// <summary>Explizite Übernahme aus der ComboBox (Code-behind) — unabhängig vom Binding.</summary>
    public void SetDraftMode(ModeOptionViewModel? option)
    {
        if (option is not null)
        {
            DraftMode = option;
        }
    }

    private ActivityOptionViewModel PickDefaultActivity() =>
        ActivityOptions.FirstOrDefault(a => a.Key == "other") ?? ActivityOptions[0];

    private ThresholdOptionViewModel PickDefaultThreshold() =>
        ThresholdOptions.FirstOrDefault(t => t.Seconds == _host.Settings.Current.DefaultThresholdSeconds)
        ?? ThresholdOptions.First(t => t.Seconds == 60);

    [RelayCommand]
    private void SaveDraft()
    {
        if (!IsDraftOpen || _draftKey.Length == 0 || DraftActivity is null || DraftThreshold is null)
        {
            return;
        }
        var now = _host.Clock.NowMs;
        var existing = _host.Store.GetRules().FirstOrDefault(r =>
            string.Equals(r.ProgramKey, _draftKey, StringComparison.OrdinalIgnoreCase));
        _host.Store.UpsertRule(new TrackingRule
        {
            ProgramKey = _draftKey,
            ProgramName = _draftName,
            ActivityKey = DraftActivity.Key,
            ThresholdSeconds = DraftThreshold.Seconds,
            Mode = DraftMode?.Mode ?? RecordMode.Foreground,
            Enabled = existing?.Enabled ?? true,
            CreatedAtMs = existing?.CreatedAtMs ?? now,
            UpdatedAtMs = now,
        });
        _host.Tracker.NotifyRulesChanged();
        IsDraftOpen = false;
        ReloadAll();
    }

    [RelayCommand]
    private void CancelDraft() => IsDraftOpen = false;

    public void DeleteRule(RuleRowViewModel row)
    {
        _host.Store.DeleteRule(row.ProgramKey);
        _host.Tracker.NotifyRulesChanged();
        ReloadAll();
    }

    public void OnRuleToggled(RuleRowViewModel row, bool enabled)
    {
        var existing = _host.Store.GetRules().FirstOrDefault(r =>
            string.Equals(r.ProgramKey, row.ProgramKey, StringComparison.OrdinalIgnoreCase));
        if (existing is null)
        {
            return;
        }
        _host.Store.UpsertRule(existing with { Enabled = enabled, UpdatedAtMs = _host.Clock.NowMs });
        _host.Tracker.NotifyRulesChanged();
    }

    /// <summary>Regel-Entwurf offen? (Für das Scroll-to-Draft im View.)</summary>
    public bool DraftJustOpened { get; private set; }
}
