using Aevum.PcTracker.Core.Services;
using Xunit;

namespace Aevum.PcTracker.Core.Tests;

/// <summary>
/// Auto-Update: Auswahl der neuesten PC-Tracker-Release aus der öffentlichen
/// GitHub-Antwort — nur stabile Versionen, nur mit Windows-ZIP, ohne Token.
/// </summary>
public class UpdateServiceTests
{
    private const string Fixture = """
    [
      {
        "tag_name": "v2.3.6",
        "name": "Aevum App 2.3.6",
        "draft": false,
        "prerelease": false,
        "body": "App-Release (kein PC-Tracker)",
        "html_url": "https://github.com/Renegaded66/Aevum/releases/tag/v2.3.6",
        "assets": [ { "name": "Aevum-app.apk", "size": 100, "browser_download_url": "https://example.com/app.apk" } ]
      },
      {
        "tag_name": "pc-tracker-1.1.0",
        "name": "PC-Tracker 1.1.0",
        "draft": false,
        "prerelease": false,
        "body": "Alte Version",
        "html_url": "https://github.com/Renegaded66/Aevum/releases/tag/pc-tracker-1.1.0",
        "assets": [ { "name": "AevumPcTracker-1.1.0-win-x64.zip", "size": 57456225, "browser_download_url": "https://example.com/1.1.0.zip" } ]
      },
      {
        "tag_name": "pc-tracker-1.2.1",
        "name": "PC-Tracker 1.2.1",
        "draft": false,
        "prerelease": false,
        "body": "Notes 1.2.1",
        "html_url": "https://github.com/Renegaded66/Aevum/releases/tag/pc-tracker-1.2.1",
        "assets": [
          { "name": "checksums.txt", "size": 10, "browser_download_url": "https://example.com/sums.txt" },
          { "name": "AevumPcTracker-1.2.1-win-x64.zip", "size": 60000000, "browser_download_url": "https://example.com/1.2.1.zip" }
        ]
      },
      {
        "tag_name": "pc-tracker-2.0.0-beta",
        "name": "Beta",
        "draft": false,
        "prerelease": true,
        "body": "Beta",
        "html_url": "https://example.com",
        "assets": [ { "name": "AevumPcTracker-2.0.0-win-x64.zip", "size": 1, "browser_download_url": "https://example.com/beta.zip" } ]
      }
    ]
    """;

    [Fact]
    public void FindsNewerTrackerRelease()
    {
        var update = UpdateService.SelectNewest(Fixture, new Version(1, 2, 0));

        Assert.NotNull(update);
        Assert.Equal(new Version(1, 2, 1), update!.Version);
        Assert.Equal("pc-tracker-1.2.1", update.Tag);
        Assert.Equal("https://example.com/1.2.1.zip", update.ZipUrl);
        Assert.Equal(60_000_000, update.ZipSizeBytes);
        Assert.Equal("Notes 1.2.1", update.Notes);
    }

    [Fact]
    public void ReturnsNull_WhenAlreadyUpToDate()
    {
        Assert.Null(UpdateService.SelectNewest(Fixture, new Version(1, 2, 1)));
        Assert.Null(UpdateService.SelectNewest(Fixture, new Version(1, 3, 0)));
    }

    [Fact]
    public void IgnoresAppReleases_Drafts_Prereleases()
    {
        const string json = """
        [
          { "tag_name": "v9.9.9", "draft": false, "prerelease": false, "assets": [ { "name": "a-win-x64.zip", "browser_download_url": "https://x/1" } ] },
          { "tag_name": "pc-tracker-9.0.0", "draft": true, "prerelease": false, "assets": [ { "name": "b-win-x64.zip", "browser_download_url": "https://x/2" } ] },
          { "tag_name": "pc-tracker-8.0.0", "draft": false, "prerelease": true, "assets": [ { "name": "c-win-x64.zip", "browser_download_url": "https://x/3" } ] }
        ]
        """;

        Assert.Null(UpdateService.SelectNewest(json, new Version(1, 0, 0)));
    }

    [Fact]
    public void IgnoresReleaseWithoutWindowsZip()
    {
        const string json = """
        [
          { "tag_name": "pc-tracker-3.0.0", "draft": false, "prerelease": false, "assets": [ { "name": "source.tar.gz", "browser_download_url": "https://x/1" } ] }
        ]
        """;

        Assert.Null(UpdateService.SelectNewest(json, new Version(1, 0, 0)));
    }

    [Fact]
    public void PicksHighestVersion_NotListOrder()
    {
        const string json = """
        [
          { "tag_name": "pc-tracker-1.10.0", "draft": false, "prerelease": false, "assets": [ { "name": "a-win-x64.zip", "browser_download_url": "https://x/new" } ] },
          { "tag_name": "pc-tracker-1.9.0", "draft": false, "prerelease": false, "assets": [ { "name": "b-win-x64.zip", "browser_download_url": "https://x/old" } ] }
        ]
        """;

        var update = UpdateService.SelectNewest(json, new Version(1, 9, 0));

        Assert.NotNull(update);
        Assert.Equal(new Version(1, 10, 0), update!.Version);
        Assert.Equal("https://x/new", update.ZipUrl);
    }

    [Fact]
    public void MalformedJson_ReturnsNull()
    {
        Assert.Null(UpdateService.SelectNewest("not json at all", new Version(1, 0, 0)));
    }
}
