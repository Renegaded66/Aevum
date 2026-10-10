using Aevum.PcTracker.Core.Models;
using Aevum.PcTracker.Core.Services;
using Xunit;

namespace Aevum.PcTracker.Core.Tests;

public class SessionStoreTests : IDisposable
{
    private readonly string _dir;
    private readonly string _dbPath;

    public SessionStoreTests()
    {
        _dir = Path.Combine(Path.GetTempPath(), "aevum-pctracker-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_dir);
        _dbPath = Path.Combine(_dir, "test.sqlite");
    }

    public void Dispose()
    {
        try
        {
            Directory.Delete(_dir, recursive: true);
        }
        catch
        {
            // Aufräumen ist best effort.
        }
    }

    private SessionStore NewStore()
    {
        var store = new SessionStore(_dbPath);
        store.Open();
        return store;
    }

    private static TrackedSession Session(string id, long start, long? end, long lastSeen) => new()
    {
        Id = id,
        ProgramKey = "minecraft.exe",
        ProgramName = "Minecraft",
        ActivityKey = "gaming",
        StartAtMs = start,
        EndAtMs = end,
        LastSeenAtMs = lastSeen,
        TimezoneId = "Europe/Berlin",
        CreatedAtMs = start,
    };

    [Fact]
    public void OpenSession_RepairFinalizesAtLastSeen()
    {
        using var store = NewStore();
        store.Apply(new[] { Session("a", 1_000, null, 5_000) });

        Assert.Equal(1, store.RepairOpenSessions());

        var sessions = store.QueryCompletedSessions(0, 100);
        var s = Assert.Single(sessions);
        Assert.Equal(5_000, s.EndAtMs);
    }

    [Fact]
    public void Upsert_NoDuplicates_AndEndUpdates()
    {
        using var store = NewStore();
        store.Apply(new[] { Session("a", 1_000, null, 5_000) });
        store.Apply(new[] { Session("a", 1_000, 5_000, 5_000) });

        Assert.Equal(1, store.CountSessions());
        var s = Assert.Single(store.QueryCompletedSessions(0, 100));
        Assert.Equal(5_000, s.EndAtMs);
    }

    [Fact]
    public void QuerySince_FiltersAndOrdersAscending()
    {
        using var store = NewStore();
        store.Apply(new[]
        {
            Session("a", 0, 100, 100),
            Session("b", 100, 200, 200),
            Session("c", 200, 300, 300),
        });

        var all = store.QueryCompletedSessions(0, 100);
        Assert.Equal(new[] { "a", "b", "c" }, all.Select(s => s.Id).ToArray());

        var since = store.QueryCompletedSessions(150, 100);
        Assert.Equal(new[] { "b", "c" }, since.Select(s => s.Id).ToArray());

        var limited = store.QueryCompletedSessions(0, 1);
        Assert.Equal("a", Assert.Single(limited).Id);
    }

    [Fact]
    public void QueryCompleted_IgnoresOpenSessions()
    {
        using var store = NewStore();
        store.Apply(new[]
        {
            Session("open", 0, null, 100),
            Session("closed", 0, 200, 200),
        });

        var completed = store.QueryCompletedSessions(0, 100);
        Assert.Equal("closed", Assert.Single(completed).Id);
    }

    [Fact]
    public void Purge_DeletesOldButKeepsOpenAndRecent()
    {
        using var store = NewStore();
        store.Apply(new[]
        {
            Session("old", 0, 100, 100),
            Session("new", 0, 10_000, 10_000),
            Session("open", 0, null, 20_000),
        });

        var deleted = store.PurgeSessionsBefore(5_000);

        Assert.Equal(1, deleted);
        var remaining = store.QueryRecentSessions(null, 100).Select(s => s.Id).OrderBy(x => x).ToArray();
        Assert.Equal(new[] { "new" }, remaining);
        // Die offene Session blieb erhalten (bis auf Reparatur geschützt).
        Assert.Equal(2, store.CountSessions());
    }

    [Fact]
    public void DeleteAll_RemovesEverything()
    {
        using var store = NewStore();
        store.Apply(new[] { Session("a", 0, 100, 100), Session("b", 0, 200, 200) });

        Assert.Equal(2, store.DeleteAllSessions());
        Assert.Equal(0, store.CountSessions());
    }

    [Fact]
    public void Rules_Roundtrip()
    {
        using var store = NewStore();
        var rule = new TrackingRule
        {
            ProgramKey = "minecraft.exe",
            ProgramName = "Minecraft",
            ActivityKey = "gaming",
            ThresholdSeconds = 60,
            Enabled = true,
            CreatedAtMs = 1,
            UpdatedAtMs = 1,
        };
        store.UpsertRule(rule);

        var loaded = Assert.Single(store.GetRules());
        Assert.Equal("gaming", loaded.ActivityKey);
        Assert.True(loaded.Enabled);

        store.UpsertRule(rule with { ActivityKey = "work", ThresholdSeconds = 300, Enabled = false, UpdatedAtMs = 2 });
        var updated = Assert.Single(store.GetRules());
        Assert.Equal("work", updated.ActivityKey);
        Assert.Equal(300, updated.ThresholdSeconds);
        Assert.False(updated.Enabled);

        store.DeleteRule("minecraft.exe");
        Assert.Empty(store.GetRules());
    }

    [Fact]
    public void Programs_UpsertKeepsPathAndUpdatesSeen()
    {
        using var store = NewStore();
        store.UpsertProgramSeen("minecraft.exe", "Minecraft", @"C:\games\minecraft.exe", 1_000, hidden: false);
        store.UpsertProgramSeen("minecraft.exe", "Minecraft", "", 5_000, hidden: false);

        var entry = Assert.Single(store.GetPrograms());
        Assert.Equal(5_000, entry.LastSeenAtMs);
        Assert.Equal(1_000, entry.FirstSeenAtMs);
        Assert.Equal(@"C:\games\minecraft.exe", entry.Path);
        Assert.False(entry.Hidden);
    }

    [Fact]
    public void Settings_Roundtrip()
    {
        using var store = NewStore();
        Assert.Null(store.GetSetting("missing"));
        store.SetSetting("x", "1");
        store.SetSetting("x", "2");
        Assert.Equal("2", store.GetSetting("x"));
    }
}
