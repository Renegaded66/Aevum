using System.Net;
using Aevum.PcTracker.Core.Models;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Server.Kestrel.Core;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;

namespace Aevum.PcTracker.Core.Services;

/// <summary>
/// Der eingebettete LAN-Server (Kestrel): liefert die abgeschlossenen Sessions an
/// die Aevum-App und wickelt die Kopplung ab. Alles per Token geschützt —
/// Spezifikation siehe docs/PC-TRACKER-PROTOCOL.md.
/// </summary>
public sealed class ApiServer : IAsyncDisposable
{
    public const int ProtocolVersion = 1;

    private readonly SessionStore _store;
    private readonly SettingsService _settings;
    private readonly TokenService _tokens;
    private readonly PairingManager _pairing;
    private readonly IClock _clock;
    private readonly string _version;

    private WebApplication? _app;

    /// <summary>Tatsächlicher Port nach dem Start (bei Port 0 der vergebene).</summary>
    public int BoundPort { get; private set; }

    /// <summary>Zeitpunkt des letzten authentifizierten Abrufs („Zuletzt abgerufen …").</summary>
    public long LastClientAccessMs { get; private set; }

    public event Action? ClientAccessed;

    public ApiServer(
        SessionStore store,
        SettingsService settings,
        TokenService tokens,
        PairingManager pairing,
        IClock clock,
        string version)
    {
        _store = store;
        _settings = settings;
        _tokens = tokens;
        _pairing = pairing;
        _clock = clock;
        _version = version;
    }

    public bool IsRunning => _app is not null;

    public async Task StartAsync(string bindAddress, int port, CancellationToken ct = default)
    {
        if (_app is not null)
        {
            return;
        }
        var builder = WebApplication.CreateSlimBuilder();
        builder.Logging.ClearProviders();
        builder.WebHost.ConfigureKestrel(options =>
        {
            options.Listen(IPAddress.Parse(bindAddress), port);
            options.Limits.MaxRequestBodySize = 64 * 1024;
        });

        var app = builder.Build();
        MapEndpoints(app);
        await app.StartAsync(ct);

        var addresses = app.Services
            .GetService<Microsoft.AspNetCore.Hosting.Server.IServer>()?
            .Features.Get<Microsoft.AspNetCore.Hosting.Server.Features.IServerAddressesFeature>();
        BoundPort = addresses is null ? port : ParsePort(addresses.Addresses, port);
        _app = app;
    }

    public async Task StopAsync(CancellationToken ct = default)
    {
        if (_app is null)
        {
            return;
        }
        await _app.StopAsync(ct);
        await _app.DisposeAsync();
        _app = null;
    }

    public async ValueTask DisposeAsync() => await StopAsync();

    private static int ParsePort(IEnumerable<string> addresses, int fallback)
    {
        foreach (var address in addresses)
        {
            if (Uri.TryCreate(address, UriKind.Absolute, out var uri) && uri.Port > 0)
            {
                return uri.Port;
            }
        }
        return fallback;
    }

    private void MapEndpoints(WebApplication app)
    {
        app.MapGet("/api/health", () => Results.Json(new { ok = true }));

        app.MapGet("/api/info", () =>
        {
            var s = _settings.Current;
            return Results.Json(new
            {
                app = "aevum-pc-tracker",
                protocol = ProtocolVersion,
                version = _version,
                deviceId = s.DeviceId,
                name = s.DeviceName,
                apiPort = BoundPort > 0 ? BoundPort : s.ApiPort,
                pairingActive = _pairing.Enabled,
                trackingPaused = s.TrackingPaused,
                retentionDays = s.RetentionDays,
            });
        });

        app.MapPost("/api/pair", async (HttpContext ctx) =>
        {
            PairRequest? request = null;
            try
            {
                request = await ctx.Request.ReadFromJsonAsync<PairRequest>();
            }
            catch
            {
                // Unlesbarer Body zählt als Fehlversuch (rate-limitiert).
            }
            var result = _pairing.TryPair(request?.Code, _clock.NowMs);
            return result.Outcome switch
            {
                PairOutcome.Success => Results.Json(new
                {
                    protocol = ProtocolVersion,
                    token = _tokens.Token,
                    deviceId = _settings.Current.DeviceId,
                    name = _settings.Current.DeviceName,
                }),
                PairOutcome.Locked => Results.Json(
                    new { error = "rate_limited", retryAfterSeconds = result.RetryAfterSeconds },
                    statusCode: StatusCodes.Status429TooManyRequests),
                // „Code falsch" und „Kopplung nicht aktiv" sind nach außen gleich —
                // den Modus verrät nur pairingActive in /api/info.
                _ => Results.Json(new { error = "invalid_code" }, statusCode: StatusCodes.Status401Unauthorized),
            };
        });

        app.MapGet("/api/sessions", (HttpContext ctx) =>
        {
            if (!Authorize(ctx, out var failure))
            {
                return failure!;
            }
            var since = ParseLong(ctx.Request.Query["since"]) ?? 0;
            var limit = (int)Math.Clamp(ParseLong(ctx.Request.Query["limit"]) ?? 200, 1, 500);
            var sessions = _store.QueryCompletedSessions(since, limit);

            LastClientAccessMs = _clock.NowMs;
            ClientAccessed?.Invoke();

            var s = _settings.Current;
            return Results.Json(new
            {
                protocol = ProtocolVersion,
                serverTime = _clock.NowMs,
                device = new { id = s.DeviceId, name = s.DeviceName },
                sessions = sessions.Select(MapSession).ToList(),
            });
        });
    }

    private static object MapSession(TrackedSession session)
    {
        var activity = ActivityCatalog.ByKeyOrOther(session.ActivityKey);
        return new
        {
            id = session.Id,
            program = session.ProgramName,
            programKey = session.ProgramKey,
            activityKey = activity.Key,
            activityName = activity.Name,
            categoryKey = activity.CategoryKey,
            icon = activity.Icon,
            startAt = session.StartAtMs,
            endAt = session.EndAtMs,
            timezoneId = session.TimezoneId,
        };
    }

    private bool Authorize(HttpContext ctx, out IResult? failure)
    {
        var token = ctx.Request.Headers["X-Aevum-Token"].FirstOrDefault();
        if (string.IsNullOrEmpty(token))
        {
            var auth = ctx.Request.Headers.Authorization.FirstOrDefault();
            if (auth is not null && auth.StartsWith("Bearer ", StringComparison.OrdinalIgnoreCase))
            {
                token = auth["Bearer ".Length..].Trim();
            }
        }
        if (!_tokens.Validate(token))
        {
            failure = Results.Json(new { error = "unauthorized" }, statusCode: StatusCodes.Status401Unauthorized);
            return false;
        }
        failure = null;
        return true;
    }

    private static long? ParseLong(string? value)
        => long.TryParse(value, out var parsed) ? parsed : null;

    public sealed record PairRequest(string? Code, string? ClientName);
}
