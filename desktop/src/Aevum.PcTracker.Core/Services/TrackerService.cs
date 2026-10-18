using Aevum.PcTracker.Core.Models;

namespace Aevum.PcTracker.Core.Services;

/// <summary>
/// Ergebnis eines manuellen Starts (PC 1.6.0: benannte Gründe statt bool —
/// die UI unterscheidet „Handy hat Vorrang" und „Stand nicht verifiziert").
/// </summary>
public enum ManualStartOutcome
{
    Started = 0,
    /// <summary>Das Handy zeichnet gerade auf (frische Meldung) — Vorrang.</summary>
    BlockedByPhone,
    /// <summary>Handy-Stand nicht frisch bestätigt — der PC startet nichts.</summary>
    Unverified,
    /// <summary>
    /// PC 1.7.0: Gekoppelt — der Start wurde als BEFEHL ans Handy geschickt.
    /// Der PC zeichnet selbst nichts auf; die Anzeige kommt über den Live-Kanal.
    /// </summary>
    SentToPhone,
}

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

    /// <summary>
    /// PC 1.5.0/1.6.0: Liefert das Verifikations-Urteil des Live-Kanals zum
    /// Zeitpunkt X. Null = kein Kanal angeschlossen (Tests/Headless) →
    /// wie „nicht gekoppelt": der PC zeichnet normal auf.
    /// </summary>
    private readonly Func<long, PhoneGateVerdict>? _phoneGate;

    /// <summary>
    /// PC 1.7.0: Schickt „starte Aktivität X" ans gekoppelte Handy.
    /// Rückgabe false = kein Handy bekannt → der PC startet wie bisher lokal.
    /// </summary>
    private readonly Func<string, bool>? _remoteStart;

    private Timer? _timer;
    private long _lastCatalogWriteMs;
    private string? _lastCatalogKey;

    /// <summary>Wird nach jedem Tick ausgelöst (UI aktualisiert die Live-Anzeige).</summary>
    public event Action? StateChanged;

    public TrackerService(
        IForegroundProbe probe,
        SessionStore store,
        SettingsService settings,
        IClock clock,
        Func<long, PhoneGateVerdict>? phoneGate = null,
        Func<string, bool>? remoteStart = null)
    {
        _probe = probe;
        _store = store;
        _settings = settings;
        _clock = clock;
        _phoneGate = phoneGate;
        _remoteStart = remoteStart;
    }

    public TrackedSession? ActiveSession => _engine.Active;

    public ForegroundSample? LastForeground { get; private set; }
    public long LastIdleMs { get; private set; }

    /// <summary>
    /// PC 1.6.0: Das aktuelle Gate-Urteil des Live-Kanals (Dashboard +
    /// Start-Erlaubnis). Ohne Kanal = NotCoupled (PC zeichnet wie immer).
    /// </summary>
    public PhoneGateVerdict PhoneVerdict => _phoneGate?.Invoke(_clock.NowMs) ?? PhoneGateVerdict.NotCoupled;

    /// <summary>PC 1.5.0: Zeichnet gerade das Handy auf? (Handy hat Vorrang)</summary>
    public bool IsPhoneRecording => PhoneVerdict == PhoneGateVerdict.Recording;

    /// <summary>Läuft die echte (Windows-)Probe? Sonst zeigt die UI einen Hinweis.</summary>
    public bool ProbeAvailable => _probe is not NullForegroundProbe;

    /// <summary>Gerade offene Programme mit Fenster („Jetzt auf diesem PC“ im Regeln-Tab).</summary>
    public IReadOnlyList<RunningProgram> GetRunningPrograms() => _probe.GetRunningPrograms();

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

    /// <summary>
    /// PC 1.3.0 (M20): Der Nutzer startet eine Aktivität aus dem Dashboard —
    /// sofort oder nach Vorlaufzeit (den Countdown zählt das UI; hier kommt nur
    /// der Ablauf an). Manuelle Sessions haben Vorrang vor Regeln und allgemeiner
    /// Aufzeichnung; beendet werden sie über den Stop-Knopf
    /// (<see cref="StopActiveSession"/>) oder die Pause.
    ///
    /// PC 1.5.0: Zeichnet gerade das HANDY auf, wird der Start abgelehnt
    /// — „immer nur eine Sache, Handy hat Vorrang". PC 1.6.0: Dazu das
    /// Verifikations-Gate — ohne frische Bestätigung („am Handy läuft
    /// nichts") startet auch der manuelle Weg nichts (eigener Grund, die
    /// UI erklärt ihn). So blitzt nie eine Session auf, die der nächste
    /// Tick sofort wieder beenden müsste.
    /// </summary>
    public ManualStartOutcome StartManualActivity(string activityKey)
    {
        // PC 1.7.0 (Devon: „Der desktop soll also sozusagen nur ans handy senden
        // ‚Du musst jetzt activity x starten/stoppen'"): Ist ein Handy bekannt,
        // geht der Start als Befehl dorthin — das Handy zeichnet auf, der PC
        // zeigt die Session über den Live-Kanal an. Das Gate gilt nur noch für
        // die LOKALE Aufzeichnung; ein Befehl ans Handy kann nichts doppelt
        // starten (das Handy entscheidet selbst, siehe PcRemoteCommandPolicy).
        if (_remoteStart is not null && _remoteStart(activityKey))
        {
            StateChanged?.Invoke();
            return ManualStartOutcome.SentToPhone;
        }

        var verdict = PhoneVerdict;
        if (verdict == PhoneGateVerdict.Recording)
        {
            return ManualStartOutcome.BlockedByPhone;
        }
        if (verdict == PhoneGateVerdict.Unverified)
        {
            return ManualStartOutcome.Unverified;
        }
        var upserts = _engine.StartManual(activityKey, TimeHelper.LocalIanaTimeZoneId(), _clock.NowMs);
        if (upserts.Count > 0)
        {
            _store.Apply(upserts);
        }
        StateChanged?.Invoke();
        return ManualStartOutcome.Started;
    }

    /// <summary>PC 1.3.0 (M20): Stop-Knopf — die laufende (manuelle) Session endet jetzt.</summary>
    public void StopActiveSession()
    {
        var upserts = _engine.StopActive(_clock.NowMs);
        if (upserts.Count > 0)
        {
            _store.Apply(upserts);
        }
        StateChanged?.Invoke();
    }

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
        var rules = _store.GetRules();

        // Prozessliste nur abfragen, wenn überhaupt Hintergrund-Regeln existieren
        // (sonst kostet jede Abfrage unnötig — sie ist der teuerste Probe-Teil).
        IReadOnlyList<string> runningKeys = Array.Empty<string>();
        if (rules.Any(r => r.Enabled && r.Mode == RecordMode.WhileRunning))
        {
            runningKeys = _probe.GetRunningPrograms().Select(p => p.ProgramKey).ToList();
        }

        // PC 1.6.0: Ein Urteil pro Tick — Engine und UI sehen denselben Stand.
        var verdict = _phoneGate?.Invoke(now) ?? PhoneGateVerdict.NotCoupled;

        var upserts = _engine.Tick(new EngineTick
        {
            NowMs = now,
            Foreground = foreground,
            Rules = rules,
            Paused = settings.TrackingPaused,
            IdleEnabled = settings.IdleEnabled,
            IdleThresholdMs = settings.IdleThresholdSeconds * 1000L,
            IdleMs = idleMs,
            TimezoneId = TimeHelper.LocalIanaTimeZoneId(),
            RunningPrograms = runningKeys,
            GeneralEnabled = settings.GeneralEnabled,
            GeneralActivityKey = settings.GeneralActivityKey,
            AudioActive = _probe.IsAudioPlaying(),
            // PC 1.5.0: Handy-Vorrang — während einer Handy-Aufzeichnung stoppt
            // die Engine alle PC-Sessions und startet nichts Neues.
            // PC 1.6.0: Verifikations-Gate — nur mit frischer „nichts läuft"-
            // Bestätigung startet überhaupt etwas Neues.
            PhoneRecording = verdict == PhoneGateVerdict.Recording,
            PhoneVerifiedIdle = verdict is PhoneGateVerdict.VerifiedIdle
                or PhoneGateVerdict.NotCoupled,
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
