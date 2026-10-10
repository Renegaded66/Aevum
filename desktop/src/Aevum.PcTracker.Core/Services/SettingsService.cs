using System.Text.Json;
using Aevum.PcTracker.Core.Models;

namespace Aevum.PcTracker.Core.Services;

/// <summary>
/// Lädt/Speichert <see cref="AppSettings"/> als JSON-Blob in der settings-Tabelle.
/// Beim ersten Start werden Geräte-ID, Name und API-Token erzeugt.
/// </summary>
public sealed class SettingsService
{
    private const string BlobKey = "app_settings_v1";

    private readonly SessionStore _store;
    private readonly IClock _clock;

    public AppSettings Current { get; private set; } = new();

    /// <summary>Wird nach jedem Speichern ausgelöst (UI + Tracker aktualisieren sich).</summary>
    public event Action? Changed;

    public SettingsService(SessionStore store, IClock clock)
    {
        _store = store;
        _clock = clock;
        Load();
    }

    private static readonly JsonSerializerOptions JsonOpts = new(JsonSerializerDefaults.Web);

    private void Load()
    {
        var raw = _store.GetSetting(BlobKey);
        if (!string.IsNullOrWhiteSpace(raw))
        {
            try
            {
                Current = JsonSerializer.Deserialize<AppSettings>(raw, JsonOpts) ?? new AppSettings();
            }
            catch (JsonException)
            {
                Current = new AppSettings();
            }
        }
        else
        {
            Current = new AppSettings();
        }

        // Erste-Inbetriebnahme: Gerät identifizieren + Token erzeugen.
        var dirty = false;
        if (string.IsNullOrWhiteSpace(Current.DeviceId))
        {
            Current.DeviceId = Guid.NewGuid().ToString();
            dirty = true;
        }
        if (string.IsNullOrWhiteSpace(Current.DeviceName))
        {
            Current.DeviceName = SafeMachineName();
            dirty = true;
        }
        if (string.IsNullOrWhiteSpace(Current.ApiToken))
        {
            Current.ApiToken = TokenService.GenerateToken();
            dirty = true;
        }
        if (Current.CreatedAtMs == 0)
        {
            Current.CreatedAtMs = _clock.NowMs;
            dirty = true;
        }
        if (dirty)
        {
            Persist();
        }
    }

    private static string SafeMachineName()
    {
        try
        {
            var name = Environment.MachineName;
            return string.IsNullOrWhiteSpace(name) ? "PC" : name;
        }
        catch
        {
            return "PC";
        }
    }

    /// <summary>Ändert Einstellungen und speichert (Kopie → mutate → speichern).</summary>
    public void Update(Action<AppSettings> mutate)
    {
        mutate(Current);
        Persist();
    }

    private void Persist()
    {
        _store.SetSetting(BlobKey, JsonSerializer.Serialize(Current, JsonOpts));
        Changed?.Invoke();
    }
}

/// <summary>Token-Erzeugung, -Rotation und -Prüfung (Prüfung zeitkonstant).</summary>
public sealed class TokenService
{
    private readonly SettingsService _settings;

    public TokenService(SettingsService settings)
    {
        _settings = settings;
    }

    public string Token => _settings.Current.ApiToken;

    public static string GenerateToken()
        => Convert.ToHexString(System.Security.Cryptography.RandomNumberGenerator.GetBytes(32)).ToLowerInvariant();

    /// <summary>Neues Token („Zugang zurücksetzen"): alle gekoppelten Handys müssen neu koppeln.</summary>
    public string Rotate()
    {
        var next = GenerateToken();
        _settings.Update(s => s.ApiToken = next);
        return next;
    }

    public bool Validate(string? candidate)
    {
        if (string.IsNullOrEmpty(candidate))
        {
            return false;
        }
        var expected = Token;
        if (expected.Length != candidate.Length)
        {
            return false;
        }
        return System.Security.Cryptography.CryptographicOperations.FixedTimeEquals(
            System.Text.Encoding.ASCII.GetBytes(expected),
            System.Text.Encoding.ASCII.GetBytes(candidate));
    }
}
