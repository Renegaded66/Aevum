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
    /// <summary>PC 1.4.0: Live-Zustand der Handy-Aufzeichnung + Steuerbefehle (Dashboard-Karte).</summary>
    public PhoneLiveRegistry PhoneLive { get; }
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
        PhoneLive = new PhoneLiveRegistry();
        Server = new ApiServer(Store, Settings, Tokens, Pairing, Clock, Version, PhoneLive);
        Updates = new UpdateService();
        Installer = new UpdateInstaller();
        Discovery = new DiscoveryResponder(Settings.Current.DiscoveryPort, () => new AnnouncePayload(
            Settings.Current.DeviceId,
            Settings.Current.DeviceName,
            Server.IsRunning && Server.BoundPort > 0 ? Server.BoundPort : Settings.Current.ApiPort,
            Pairing.Enabled));
        // PC 1.5.0: Handy-Vorrang — der Tracker fragt bei jedem Tick (und vor
        // jedem manuellen Start), ob das Handy gerade aufzeichnet.
        // PC 1.5.2: Dafür gilt das geduldige Prioritätsfenster (5 min statt
        // 45 s) — plus Übergangs-Logging, damit Funkstille-Fälle nie wieder
        // Rätselraten sind.
        Tracker = new TrackerService(ProbeFactory.Create(), Store, Settings, Clock,
            phoneRecording: now =>
            {
                var recording = PhoneLive.IsRecordingForPriority(now);
                if (_lastPhoneRecording != recording)
                {
                    var status = PhoneLive.GetStatus(now);
                    var age = status.Session is null ? -1 : now - status.Session.LastUpdateMs;
                    UpdateLog.AppendTo(DataDirectory, "phone-priority.log",
                        recording
                            ? $"vorrang: Handy-Aufzeichnung erkannt (letzte Meldung vor {age / 1000}s)"
                            : $"vorrang: PC wieder frei — {(age < 0 ? "Handy meldete keine Session" : $"letzte Meldung vor {age / 1000}s (> {PhoneLiveRegistry.PriorityWindowMs / 60_000} min)")}");
                    _lastPhoneRecording = recording;
                }
                return recording;
            });
    }

    private bool? _lastPhoneRecording;

    /// <summary>
    /// PC 1.5.0/1.5.2: Zeichnet gerade das Handy auf? Für den Vorrang zählt
    /// die letzte Live-Meldung bis 5 Minuten nach Eingang (PriorityWindow) —
    /// Sendelücken des Handys (Standby/Netz) dürfen den PC nicht in kurze
    /// Falsch-Aufzeichnungen treiben. Die UI zeigt die strengere 45-s-Stufe
    /// separat und ehrlich an.
    /// </summary>
    public bool IsPhoneRecording()
    {
        return PhoneLive.IsRecordingForPriority(Clock.NowMs);
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
