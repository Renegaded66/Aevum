using System.Net.Http.Json;
using System.Text;
using System.Text.Json;
using Aevum.PcTracker.Core.Models;
using Aevum.PcTracker.Core.Services;
using Xunit;

namespace Aevum.PcTracker.Core.Tests;

/// <summary>
/// Echte HTTP-Tests gegen den eingebetteten Kestrel-Server (Loopback, Port 0).
/// Damit ist der komplette Kopplungs- und Sync-Weg ohne Handy verifiziert.
/// </summary>
public class ApiServerTests : IAsyncLifetime
{
    private readonly FakeClock _clock = new(1_000_000);
    private readonly PairingManager _pairing = new();
    private readonly PhoneLiveRegistry _phoneLive = new();

    private string _dir = "";
    private SessionStore _store = null!;
    private SettingsService _settings = null!;
    private TokenService _tokens = null!;
    private ApiServer _server = null!;
    private HttpClient _client = null!;

    public async Task InitializeAsync()
    {
        _dir = Path.Combine(Path.GetTempPath(), "aevum-api-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_dir);
        _store = new SessionStore(Path.Combine(_dir, "s.sqlite"));
        _store.Open();
        _settings = new SettingsService(_store, _clock);
        _tokens = new TokenService(_settings);
        _server = new ApiServer(_store, _settings, _tokens, _pairing, _clock, "1.0.0-test", _phoneLive);
        await _server.StartAsync("127.0.0.1", 0);
        _client = new HttpClient { BaseAddress = new Uri($"http://127.0.0.1:{_server.BoundPort}") };
    }

    public async Task DisposeAsync()
    {
        _client.Dispose();
        await _server.DisposeAsync();
        _store.Dispose();
        try
        {
            Directory.Delete(_dir, recursive: true);
        }
        catch
        {
            // best effort
        }
    }

    private HttpRequestMessage Authed(HttpMethod method, string path, string? token = null)
    {
        var request = new HttpRequestMessage(method, path);
        request.Headers.Add("X-Aevum-Token", token ?? _tokens.Token);
        return request;
    }

    private void SeedSession(string id, long start, long end)
    {
        _store.Apply(new[]
        {
            new TrackedSession
            {
                Id = id,
                ProgramKey = "minecraft.exe",
                ProgramName = "Minecraft",
                ActivityKey = "gaming",
                StartAtMs = start,
                EndAtMs = end,
                LastSeenAtMs = end,
                TimezoneId = "Europe/Berlin",
                CreatedAtMs = start,
            },
        });
    }

    private static async Task<JsonElement> Json(HttpResponseMessage response)
    {
        var text = await response.Content.ReadAsStringAsync();
        return JsonDocument.Parse(text).RootElement.Clone();
    }

    [Fact]
    public async Task Health_IsOpen()
    {
        var response = await _client.GetAsync("/api/health");

        Assert.Equal(System.Net.HttpStatusCode.OK, response.StatusCode);
        Assert.True((await Json(response)).GetProperty("ok").GetBoolean());
    }

    [Fact]
    public async Task Info_HasNoSecrets()
    {
        var response = await _client.GetAsync("/api/info");
        var body = await response.Content.ReadAsStringAsync();
        var root = await Json(response);

        Assert.Equal(200, (int)response.StatusCode);
        Assert.Equal(1, root.GetProperty("protocol").GetInt32());
        Assert.Equal(_settings.Current.DeviceId, root.GetProperty("deviceId").GetString());
        Assert.False(root.GetProperty("pairingActive").GetBoolean());
        Assert.False(root.GetProperty("trackingPaused").GetBoolean());
        // Das Token darf NIRGENDS aufscheinen.
        Assert.DoesNotContain(_tokens.Token, body, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task Sessions_RequireToken()
    {
        SeedSession("a", 0, 100);

        var anonymous = await _client.GetAsync("/api/sessions");
        Assert.Equal(System.Net.HttpStatusCode.Unauthorized, anonymous.StatusCode);
        Assert.Equal("unauthorized", (await Json(anonymous)).GetProperty("error").GetString());

        var wrong = await _client.SendAsync(Authed(HttpMethod.Get, "/api/sessions", "f" + new string('0', 63)));
        Assert.Equal(System.Net.HttpStatusCode.Unauthorized, wrong.StatusCode);

        // Bearer-Variante funktioniert ebenfalls.
        var bearer = new HttpRequestMessage(HttpMethod.Get, "/api/sessions");
        bearer.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", _tokens.Token);
        Assert.Equal(System.Net.HttpStatusCode.OK, (await _client.SendAsync(bearer)).StatusCode);
    }

    [Fact]
    public async Task Sessions_ReturnsCompletedInOrder_WithProtocolFields()
    {
        SeedSession("late", 5_000, 30_000);
        SeedSession("early", 0, 10_000);
        // Offene Session darf NIE ausgeliefert werden.
        _store.Apply(new[]
        {
            new TrackedSession
            {
                Id = "open", ProgramKey = "vlc.exe", ProgramName = "VLC", ActivityKey = "video",
                StartAtMs = 0, EndAtMs = null, LastSeenAtMs = 99, TimezoneId = "Europe/Berlin", CreatedAtMs = 0,
            },
        });

        var response = await _client.SendAsync(Authed(HttpMethod.Get, "/api/sessions"));
        var root = await Json(response);

        Assert.Equal(200, (int)response.StatusCode);
        Assert.Equal(1, root.GetProperty("protocol").GetInt32());
        Assert.Equal(_settings.Current.DeviceId, root.GetProperty("device").GetProperty("id").GetString());
        Assert.Equal(_settings.Current.DeviceName, root.GetProperty("device").GetProperty("name").GetString());

        var sessions = root.GetProperty("sessions");
        Assert.Equal(2, sessions.GetArrayLength());
        Assert.Equal("early", sessions[0].GetProperty("id").GetString());
        Assert.Equal("late", sessions[1].GetProperty("id").GetString());

        var first = sessions[0];
        Assert.Equal("Minecraft", first.GetProperty("program").GetString());
        Assert.Equal("minecraft.exe", first.GetProperty("programKey").GetString());
        Assert.Equal("gaming", first.GetProperty("activityKey").GetString());
        Assert.Equal("Zocken", first.GetProperty("activityName").GetString());
        Assert.Equal("digital", first.GetProperty("categoryKey").GetString());
        Assert.Equal("🎮", first.GetProperty("icon").GetString());
        Assert.Equal(0, first.GetProperty("startAt").GetInt64());
        Assert.Equal(10_000, first.GetProperty("endAt").GetInt64());
        Assert.Equal("Europe/Berlin", first.GetProperty("timezoneId").GetString());
    }

    [Fact]
    public async Task Sessions_PaginationWorks()
    {
        SeedSession("a", 0, 10);
        SeedSession("b", 10, 20);
        SeedSession("c", 20, 30);

        var first = await Json(await _client.SendAsync(Authed(HttpMethod.Get, "/api/sessions?limit=2")));
        Assert.Equal(2, first.GetProperty("sessions").GetArrayLength());

        var lastEnd = first.GetProperty("sessions")[1].GetProperty("endAt").GetInt64();
        var second = await Json(await _client.SendAsync(Authed(HttpMethod.Get, $"/api/sessions?since={lastEnd}&limit=2")));
        var rest = second.GetProperty("sessions");
        Assert.Equal(1, rest.GetArrayLength());
        Assert.Equal("c", rest[0].GetProperty("id").GetString());
    }

    [Fact]
    public async Task Pair_Flow_ReturnsWorkingToken()
    {
        var code = _pairing.Enable();

        var response = await _client.PostAsJsonAsync("/api/pair", new { code, clientName = "Test-Handy" });
        var root = await Json(response);

        Assert.Equal(200, (int)response.StatusCode);
        Assert.Equal(1, root.GetProperty("protocol").GetInt32());
        var token = root.GetProperty("token").GetString();
        Assert.Equal(_tokens.Token, token);
        Assert.Equal(_settings.Current.DeviceId, root.GetProperty("deviceId").GetString());

        // Der frisch gekoppelte Token funktioniert sofort.
        Assert.Equal(200, (int)(await _client.SendAsync(Authed(HttpMethod.Get, "/api/sessions", token))).StatusCode);
        // Und der verwendete Code ist verbraucht.
        Assert.NotEqual(code, _pairing.CurrentCode);
    }

    [Fact]
    public async Task Pair_WrongCode_Invalid()
    {
        _pairing.Enable();

        var response = await _client.PostAsJsonAsync("/api/pair", new { code = "000000" });

        Assert.Equal(System.Net.HttpStatusCode.Unauthorized, response.StatusCode);
        Assert.Equal("invalid_code", (await Json(response)).GetProperty("error").GetString());
    }

    [Fact]
    public async Task Pair_NotActive_SameErrorAsWrongCode()
    {
        // Kopplung nicht aktiv → nach außen dieselbe Meldung (kein Orakel).
        var response = await _client.PostAsJsonAsync("/api/pair", new { code = "123456" });

        Assert.Equal(System.Net.HttpStatusCode.Unauthorized, response.StatusCode);
        Assert.Equal("invalid_code", (await Json(response)).GetProperty("error").GetString());
    }

    [Fact]
    public async Task Pair_RateLimited_AfterFiveFailures()
    {
        _pairing.Enable();

        HttpResponseMessage? last = null;
        for (var i = 0; i < 5; i++)
        {
            last = await _client.PostAsJsonAsync("/api/pair", new { code = "000000" });
        }

        Assert.Equal(System.Net.HttpStatusCode.TooManyRequests, last!.StatusCode);
        var root = await Json(last);
        Assert.Equal("rate_limited", root.GetProperty("error").GetString());
        Assert.True(root.GetProperty("retryAfterSeconds").GetInt32() > 0);
    }

    [Fact]
    public async Task GarbageBody_CountsAsFailedAttempt()
    {
        _pairing.Enable();

        var response = await _client.PostAsync("/api/pair",
            new StringContent("not json at all", Encoding.UTF8, "application/json"));

        Assert.Equal(System.Net.HttpStatusCode.Unauthorized, response.StatusCode);
    }

    // ── PC 1.7.0: Startbefehl-Kanal (/api/commands) ───────────────────────

    [Fact]
    public async Task Commands_RequireToken()
    {
        var response = await _client.GetAsync("/api/commands");

        Assert.Equal(System.Net.HttpStatusCode.Unauthorized, response.StatusCode);
    }

    [Fact]
    public async Task Commands_DeliverRemoteStartExactlyOnce()
    {
        // Devon: „Der desktop soll also sozusagen nur ans handy senden
        // ‚Du musst jetzt activity x starten/stoppen'."
        _phoneLive.RequestRemoteStart("learning", _clock.NowMs);

        var first = await Json(await _client.SendAsync(Authed(HttpMethod.Get, "/api/commands")));
        Assert.Equal("start", first.GetProperty("command").GetString());
        Assert.Equal("learning", first.GetProperty("activityKey").GetString());

        var second = await Json(await _client.SendAsync(Authed(HttpMethod.Get, "/api/commands")));
        Assert.Equal(JsonValueKind.Null, second.GetProperty("command").ValueKind);
    }

    [Fact]
    public async Task Commands_WithoutPendingStart_AreNull()
    {
        var response = await Json(await _client.SendAsync(Authed(HttpMethod.Get, "/api/commands")));

        Assert.Equal(JsonValueKind.Null, response.GetProperty("command").ValueKind);
    }
}
