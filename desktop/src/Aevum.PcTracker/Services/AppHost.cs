using Aevum.PcTracker.Core.Services;

namespace Aevum.PcTracker.Services;

/// <summary>
/// Komponiert alle Core-Dienste (Store, Settings, Token, Pairing, API-Server,
/// Discovery, Tracker) und steuert ihren Lebenszyklus. Die Oberfläche spricht
/// nur mit dem AppHost, nie direkt mit den Einzelteilen.
/// </summary>
public sealed class AppHost : IAsyncDisposable
{
    public string Version { get; }
    public string DataDirectory { get; }

    public SessionStore Store { get; }
    public SettingsService Settings { get; }
    public TokenService Tokens { get; }
    public PairingManager Pairing { get; } = new();
    public ApiServer Server { get; }
    public DiscoveryResponder Discovery { get; }
    public TrackerService Tracker { get; }
    public UpdateService Updates { get; }
    public UpdateInstaller Installer { get; }
    public IClock Clock { get; } = SystemClock.Instance;

    /// <summary>Fehler beim Start des LAN-Servers (null = läuft).</summary>
    public string? ServerError { get; private set; }

    /// <summary>Fehler beim Start der Discovery (null = läuft).</summary>
    public string? DiscoveryError { get; private set; }

    public AppHost(string? dataDirectory = null)
    {
        Version = typeof(AppHost).Assembly.GetName().Version?.ToString(3) ?? "1.0.0";
        DataDirectory = dataDirectory ?? DefaultDataDirectory();
        Directory.CreateDirectory(DataDirectory);

        Store = new SessionStore(Path.Combine(DataDirectory, "pctracker.sqlite"));
        Store.Open();
        Settings = new SettingsService(Store, Clock);
        Tokens = new TokenService(Settings);
        Server = new ApiServer(Store, Settings, Tokens, Pairing, Clock, Version);
        Updates = new UpdateService();
        Installer = new UpdateInstaller();
        Discovery = new DiscoveryResponder(Settings.Current.DiscoveryPort, () => new AnnouncePayload(
            Settings.Current.DeviceId,
            Settings.Current.DeviceName,
            Server.IsRunning && Server.BoundPort > 0 ? Server.BoundPort : Settings.Current.ApiPort,
            Pairing.Enabled));
        Tracker = new TrackerService(ProbeFactory.Create(), Store, Settings, Clock);
    }

    public static string DefaultDataDirectory()
    {
        var appData = Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData);
        if (string.IsNullOrEmpty(appData))
        {
            appData = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".aevum-pctracker");
        }
        return Path.Combine(appData, "AevumPcTracker");
    }

    public async Task StartAsync()
    {
        // Nach einem Absturz offen gebliebene Sessions abschließen und alte
        // Aufzeichnungen gemäß Aufbewahrungs-Einstellung aufräumen.
        Store.RepairOpenSessions();
        var retentionDays = Settings.Current.RetentionDays;
        if (retentionDays > 0)
        {
            Store.PurgeSessionsBefore(Clock.NowMs - retentionDays * 86_400_000L);
        }

        // Aufzeichnung zuerst — sie braucht keinen Netzwerk-Start.
        Tracker.Start();

        // LAN-Server: falls der Wunsch-Port belegt ist, die nächsten Ports
        // probieren (und den funktionierenden merken). Kein Nutzer-Eingriff nötig.
        ServerError = null;
        for (var attempt = 0; attempt < 10 && !Server.IsRunning; attempt++)
        {
            try
            {
                await Server.StartAsync("0.0.0.0", Settings.Current.ApiPort + attempt);
                ServerError = null;
                break;
            }
            catch (Exception ex)
            {
                ServerError = ex.Message;
            }
        }
        if (Server.IsRunning && Server.BoundPort != Settings.Current.ApiPort)
        {
            Settings.Update(s => s.ApiPort = Server.BoundPort);
        }

        // Discovery (UDP): gleiches Muster.
        DiscoveryError = null;
        try
        {
            Discovery.Start();
        }
        catch (Exception ex)
        {
            DiscoveryError = ex.Message;
            // Zweiter Versuch auf einem Nachbarport, dauerhaft gemerkt.
            try
            {
                var fallback = new DiscoveryResponder(Settings.Current.DiscoveryPort + 1, () => new AnnouncePayload(
                    Settings.Current.DeviceId, Settings.Current.DeviceName,
                    Server.BoundPort > 0 ? Server.BoundPort : Settings.Current.ApiPort, Pairing.Enabled));
                fallback.Start();
                Settings.Update(s => s.DiscoveryPort = Settings.Current.DiscoveryPort + 1);
                DiscoveryError = null;
                _discoveryFallback = fallback;
            }
            catch (Exception ex2)
            {
                DiscoveryError = $"{ex.Message} / {ex2.Message}";
            }
        }
    }

    private DiscoveryResponder? _discoveryFallback;

    public async Task StopAsync()
    {
        Tracker.FlushOnExit();
        Tracker.Stop();
        await Server.StopAsync();
        Discovery.Stop();
        _discoveryFallback?.Stop();
        Store.Dispose();
    }

    public async ValueTask DisposeAsync() => await StopAsync();
}

/// <summary>Wählt die passende Fenster-Probe für die Plattform.</summary>
internal static class ProbeFactory
{
    public static IForegroundProbe Create()
    {
        if (OperatingSystem.IsWindows())
        {
            return new Win32ForegroundProbe();
        }
        // Nicht-Windows (Entwicklung/Tests): nichts aufzeichnen, App läuft trotzdem.
        return new NullForegroundProbe();
    }
}
