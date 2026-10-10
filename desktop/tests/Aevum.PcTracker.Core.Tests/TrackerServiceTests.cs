using Aevum.PcTracker.Core.Models;
using Aevum.PcTracker.Core.Services;
using Xunit;

namespace Aevum.PcTracker.Core.Tests;

public class TrackerServiceTests : IDisposable
{
    private const long T = 1_000_000;

    private readonly string _dir;
    private readonly string _dbPath;
    private readonly FakeClock _clock = new(T);
    private readonly FakeProbe _probe = new();
    private SessionStore _store = null!;
    private SettingsService _settings = null!;
    private TrackerService _service = null!;

    public TrackerServiceTests()
    {
        _dir = Path.Combine(Path.GetTempPath(), "aevum-tracker-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_dir);
        _dbPath = Path.Combine(_dir, "t.sqlite");
    }

    public void Dispose()
    {
        try
        {
            Directory.Delete(_dir, recursive: true);
        }
        catch
        {
            // best effort
        }
    }

    private void Setup()
    {
        _store = new SessionStore(_dbPath);
        _store.Open();
        _settings = new SettingsService(_store, _clock);
        _service = new TrackerService(_probe, _store, _settings, _clock);
    }

    private void GivenRule(string key = "minecraft.exe", int threshold = 60, string activity = "gaming")
    {
        _store.UpsertRule(new TrackingRule
        {
            ProgramKey = key,
            ProgramName = key,
            ActivityKey = activity,
            ThresholdSeconds = threshold,
            Enabled = true,
            CreatedAtMs = T,
            UpdatedAtMs = T,
        });
    }

    [Fact]
    public void EndToEnd_SessionLifecycle()
    {
        Setup();
        GivenRule();

        _probe.Foreground = FakeProbe.Sample("minecraft.exe");
        _service.Tick();
        Assert.NotNull(_service.ActiveSession);
        Assert.Empty(_store.QueryCompletedSessions(0, 10));

        _clock.Advance(2_000);
        _service.Tick();
        Assert.NotNull(_service.ActiveSession);

        // Fenster weg — nach 70 s (über Threshold 60 s) wird abgeschlossen.
        _probe.Foreground = null;
        _clock.Advance(70_000);
        _service.Tick();

        Assert.Null(_service.ActiveSession);
        var session = Assert.Single(_store.QueryCompletedSessions(0, 10));
        Assert.Equal(T + 2_000, session.EndAtMs); // letzte Sicht, nicht jetzt
        Assert.Equal("gaming", session.ActivityKey);
    }

    [Fact]
    public void Pause_StopsAndResume_Restarts()
    {
        Setup();
        GivenRule();
        _probe.Foreground = FakeProbe.Sample("minecraft.exe");

        _service.Tick();
        Assert.NotNull(_service.ActiveSession);

        _service.RequestPause();
        Assert.Null(_service.ActiveSession);
        Assert.True(_settings.Current.TrackingPaused);
        Assert.Single(_store.QueryCompletedSessions(0, 10));

        // Während der Pause startet nichts.
        _clock.Advance(5_000);
        _service.Tick();
        Assert.Null(_service.ActiveSession);

        // Fortsetzen: zählt ab jetzt.
        _service.RequestResume();
        Assert.False(_settings.Current.TrackingPaused);
        _clock.Advance(2_000);
        _service.Tick();
        Assert.NotNull(_service.ActiveSession);
        Assert.Equal(_clock.NowMs, _service.ActiveSession!.StartAtMs);
    }

    [Fact]
    public void Catalog_RecordsSeenPrograms()
    {
        Setup();

        _probe.Foreground = FakeProbe.Sample("firefox.exe");
        _service.Tick();
        _clock.Advance(2_000);
        _probe.Foreground = FakeProbe.Sample("notepad.exe");
        _service.Tick();

        var programs = _store.GetPrograms().Select(p => p.ProgramKey).OrderBy(x => x).ToArray();
        Assert.Equal(new[] { "firefox.exe", "notepad.exe" }, programs);
    }

    [Fact]
    public void Idle_EndsSession_ClampedToStart()
    {
        Setup();
        GivenRule();
        _probe.Foreground = FakeProbe.Sample("minecraft.exe");
        _service.Tick();

        // Nutzer ist seit Ewigkeiten weg, obwohl das Fenster noch vorne ist.
        _probe.IdleMs = 1_000_000;
        _clock.Advance(2_000);
        _service.Tick();

        Assert.Null(_service.ActiveSession);
        var session = Assert.Single(_store.QueryCompletedSessions(0, 10));
        Assert.Equal(T, session.EndAtMs); // auf den Start geklemmt, nie negativ
    }

    [Fact]
    public void Start_RepairsLeftoverOpenSessions()
    {
        Setup();
        _store.Apply(new[]
        {
            new TrackedSession
            {
                Id = "crashed", ProgramKey = "minecraft.exe", ProgramName = "Minecraft",
                ActivityKey = "gaming", StartAtMs = T - 60_000, EndAtMs = null,
                LastSeenAtMs = T - 30_000, TimezoneId = "Europe/Berlin", CreatedAtMs = T - 60_000,
            },
        });

        _service.Start();
        _service.Stop();

        var session = Assert.Single(_store.QueryCompletedSessions(0, 10));
        Assert.Equal(T - 30_000, session.EndAtMs);
    }

    [Fact]
    public void NoRule_NoTracking()
    {
        Setup();
        _probe.Foreground = FakeProbe.Sample("unknown.exe");
        _service.Tick();

        Assert.Null(_service.ActiveSession);
        Assert.Equal(0, _store.CountSessions());
    }
}

public class ProgramNamesTests
{
    [Theory]
    [InlineData("minecraft.exe", "Minecraft")]
    [InlineData("some-tool.exe", "Some Tool")]
    [InlineData("foo_bar.exe", "Foo Bar")]
    [InlineData("xyz", "Xyz")]
    public void DisplayNameFor_BeautifiesFilenames(string key, string expected)
    {
        Assert.Equal(expected, ProgramNames.DisplayNameFor(key));
    }

    [Fact]
    public void KeyFor_LowercasesFileName()
    {
        Assert.Equal("minecraft.exe", ProgramNames.KeyFor("Minecraft.EXE"));
    }

    [Fact]
    public void IsSystemExecutable_FiltersWindowsFolder()
    {
        Assert.True(ProgramNames.IsSystemExecutable(@"C:\Windows\System32\cmd.exe", @"C:\Windows"));
        Assert.True(ProgramNames.IsSystemExecutable(@"C:\Windows\explorer.exe", @"C:\Windows"));
        Assert.False(ProgramNames.IsSystemExecutable(@"C:\Games\minecraft.exe", @"C:\Windows"));
        Assert.False(ProgramNames.IsSystemExecutable("", @"C:\Windows"));
    }

    [Fact]
    public void Catalog_KeysAreUnique_AndResolvable()
    {
        var keys = ActivityCatalog.All.Select(a => a.Key).ToArray();
        Assert.Equal(keys.Length, keys.Distinct().Count());
        foreach (var key in keys)
        {
            Assert.NotNull(ActivityCatalog.ByKey(key));
        }
        // Bekannte Programme schlagen nur existierende Aktivitäten vor.
        foreach (var entry in KnownPrograms.All)
        {
            Assert.NotNull(ActivityCatalog.ByKey(entry.SuggestedActivityKey));
        }
    }
}
