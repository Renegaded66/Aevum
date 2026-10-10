using System.Collections.ObjectModel;
using Aevum.PcTracker.Core.Models;
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
        SelectCommand = new RelayCommand(() => owner.SelectCandidate(this));
    }
}

public sealed class ActivityOptionViewModel
{
    public string Key { get; }
    public string Display { get; }

    public ActivityOptionViewModel(string key, string icon, string name)
    {
        Key = key;
        Display = $"{icon} {name}";
    }
}

public sealed class ThresholdOptionViewModel
{
    public int Seconds { get; }
    public string Label { get; }

    public ThresholdOptionViewModel(int seconds)
    {
        Seconds = seconds;
        Label = ThresholdOptions.Label(seconds);
    }
}

public partial class RuleRowViewModel : ObservableObject
{
    private readonly RulesViewModel _owner;

    public string ProgramKey { get; }
    public string ProgramName { get; }
    public string ActivityDisplay { get; }
    public string ThresholdLabel { get; }
    public IRelayCommand DeleteCommand { get; }

    [ObservableProperty]
    private bool _isEnabled;

    public RuleRowViewModel(TrackingRule rule, RulesViewModel owner)
    {
        _owner = owner;
        ProgramKey = rule.ProgramKey;
        ProgramName = rule.ProgramName;
        var activity = ActivityCatalog.ByKeyOrOther(rule.ActivityKey);
        ActivityDisplay = $"{activity.Icon} {activity.Name}";
        ThresholdLabel = $"Endet nach {ThresholdOptions.Label(rule.ThresholdSeconds)} ohne Programm im Vordergrund";
        _isEnabled = rule.Enabled; // direkt ins Feld: kein Toggle-Event beim Laden
        DeleteCommand = new RelayCommand(() => _owner.DeleteRule(this));
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

    [ObservableProperty] private string _searchText = "";
    [ObservableProperty] private bool _hasRules;
    [ObservableProperty] private bool _isDraftOpen;
    [ObservableProperty] private string _draftTitle = "";
    [ObservableProperty] private string _draftSubtitle = "";
    [ObservableProperty] private ActivityOptionViewModel? _draftActivity;
    [ObservableProperty] private ThresholdOptionViewModel? _draftThreshold;

    private string _draftKey = "";
    private string _draftName = "";

    public RulesViewModel(AppHost host)
    {
        _host = host;
        foreach (var activity in ActivityCatalog.All)
        {
            ActivityOptions.Add(new ActivityOptionViewModel(activity.Key, activity.Icon, activity.Name));
        }
        foreach (var seconds in Core.Models.ThresholdOptions.Seconds)
        {
            ThresholdOptions.Add(new ThresholdOptionViewModel(seconds));
        }
        Refresh();
    }

    public void Refresh() => ReloadAll();

    partial void OnSearchTextChanged(string value) => ReloadCandidates();

    private void ReloadAll()
    {
        var rules = _host.Store.GetRules();
        Rules.Clear();
        foreach (var rule in rules)
        {
            Rules.Add(new RuleRowViewModel(rule, this));
        }
        HasRules = Rules.Count > 0;
        ReloadCandidates();
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
                "Auf diesem PC gesehen",
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
                "Vorschlag",
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

    private static bool Matches(string name, string key, string query) =>
        query.Length == 0
        || name.Contains(query, StringComparison.OrdinalIgnoreCase)
        || key.Contains(query, StringComparison.OrdinalIgnoreCase);

    private static string SuggestionTextFor(string? activityKey)
    {
        var activity = ActivityCatalog.ByKey(activityKey);
        return activity is null ? "" : $"{activity.Icon} {activity.Name}";
    }

    public void SelectCandidate(ProgramCandidateViewModel candidate)
    {
        _draftKey = candidate.ProgramKey;
        _draftName = candidate.Name;
        DraftTitle = candidate.Name;
        var existing = _host.Store.GetRules().FirstOrDefault(r =>
            string.Equals(r.ProgramKey, candidate.ProgramKey, StringComparison.OrdinalIgnoreCase));
        if (existing is not null)
        {
            DraftSubtitle = "Bestehende Zuordnung bearbeiten";
            DraftActivity = ActivityOptions.FirstOrDefault(a => a.Key == existing.ActivityKey) ?? PickDefaultActivity();
            DraftThreshold = ThresholdOptions.FirstOrDefault(t => t.Seconds == existing.ThresholdSeconds) ?? PickDefaultThreshold();
        }
        else
        {
            DraftSubtitle = candidate.Badge;
            var suggested = KnownPrograms.All.FirstOrDefault(k =>
                string.Equals(k.Exe, candidate.ProgramKey, StringComparison.OrdinalIgnoreCase))?.SuggestedActivityKey;
            DraftActivity = ActivityOptions.FirstOrDefault(a => a.Key == suggested) ?? PickDefaultActivity();
            DraftThreshold = PickDefaultThreshold();
        }
        IsDraftOpen = true;
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
}
