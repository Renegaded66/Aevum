using Aevum.PcTracker.Core.Models;

namespace Aevum.PcTracker.Core.Services;

/// <summary>
/// Der laufende Aufzeichnungsdienst: alle 2 Sekunden Probe → Engine → Store.
/// Er tut nichts weiter — die gesamte Entscheidungslogik liegt in der Engine
/// (testbar), hier ist nur der Takt und die Persistenz.
/// </summary>
public sealed class TrackerService : IDisposable
{
    public const int TickIntervalMs = 2000;

    private readonly IForegroundProbe _probe;
    private readonly SessionStore _store;
    private readonly SettingsService _settings;
    private readonly IClock _clock;
    private readonly SessionEngine _engine = new();

    private Timer? _timer;
    private long _lastCatalogWriteMs;
    private string? _lastCatalogKey;

    /// <summary>Wird nach jedem Tick ausgelöst (UI aktualisiert die Live-Anzeige).</summary>
    public event Action? StateChanged;

    public TrackerService(IForegroundProbe probe, SessionStore store, SettingsService settings, IClock clock)
    {
        _probe = probe;
        _store = store;
        _settings = settings;
        _clock = clock;
    }

    public TrackedSession? ActiveSession => _engine.Active;
    public ForegroundSample? LastForeground { get; private set; }
    public long LastIdleMs { get; private set; }

    /// <summary>Läuft die echte (Windows-)Probe? Sonst zeigt die UI einen Hinweis.</summary>
    public bool ProbeAvailable => _probe is not NullForegroundProbe;

    public void Start()
    {
        _store.RepairOpenSessions();
        _timer ??= new Timer(_ => TickSafe(), null, 0, TickIntervalMs);
    }

    public void Stop()
    {
        _timer?.Dispose();
        _timer = null;
    }

    /// <summary>Nutzer drückt „Pause": laufende Session endet, nichts Neues startet.</summary>
    public void RequestPause()
    {
        var upserts = _engine.Pause(_clock.NowMs);
        if (upserts.Count > 0)
        {
            _store.Apply(upserts);
        }
        _settings.Update(s => s.TrackingPaused = true);
        StateChanged?.Invoke();
    }

    /// <summary>„Fortsetzen": ab dem nächsten Tick wird wieder aufgezeichnet.</summary>
    public void RequestResume()
    {
        _settings.Update(s => s.TrackingPaused = false);
        StateChanged?.Invoke();
    }

    /// <summary>Nach Regel-Änderungen: Zählung beginnt ab jetzt (keine rückwirkende Aufzeichnung).</summary>
    public void NotifyRulesChanged() => _engine.NotifyRulesChanged(_clock.NowMs);

    /// <summary>Beim Beenden der App: laufende Session sauber abschließen.</summary>
    public void FlushOnExit()
    {
        var upserts = _engine.Flush();
        if (upserts.Count > 0)
        {
            _store.Apply(upserts);
        }
    }

    private void TickSafe()
    {
        try
        {
            Tick();
        }
        catch
        {
            // Ein Fehler in einem Tick darf den Dienst nie beenden.
        }
    }

    /// <summary>Aufzeichnungs-Takt (intern für Tests sichtbar).</summary>
    internal void Tick()
    {
        var now = _clock.NowMs;
        var foreground = _probe.GetForeground();
        var idleMs = _probe.GetIdleMs();
        LastForeground = foreground;
        LastIdleMs = idleMs;

        // Erkannter Programm-Katalog (gedrosselt: bei Programm-Wechsel oder alle 30 s).
        if (foreground is not null && !ProgramNames.IsSystemExecutable(foreground.Path))
        {
            if (foreground.ProgramKey != _lastCatalogKey || now - _lastCatalogWriteMs > 30_000)
            {
                _store.UpsertProgramSeen(foreground.ProgramKey, foreground.DisplayName, foreground.Path, now, hidden: false);
                _lastCatalogKey = foreground.ProgramKey;
                _lastCatalogWriteMs = now;
            }
        }

        var settings = _settings.Current;
        var upserts = _engine.Tick(new EngineTick
        {
            NowMs = now,
            Foreground = foreground,
            Rules = _store.GetRules(),
            Paused = settings.TrackingPaused,
            IdleEnabled = settings.IdleEnabled,
            IdleThresholdMs = settings.IdleThresholdSeconds * 1000L,
            IdleMs = idleMs,
            TimezoneId = TimeHelper.LocalIanaTimeZoneId(),
        });
        if (upserts.Count > 0)
        {
            _store.Apply(upserts);
        }
        StateChanged?.Invoke();
    }

    public void Dispose()
    {
        Stop();
    }
}
