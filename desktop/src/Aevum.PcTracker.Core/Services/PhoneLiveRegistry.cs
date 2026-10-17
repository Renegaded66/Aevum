using Aevum.PcTracker.Core.Models;

namespace Aevum.PcTracker.Core.Services;

/// <summary>
/// PC-Tracker 1.4.0: Der Live-Kanal zum Handy — reine, testbare Logik.
///
/// Devon: „Also auf meinem handy zeichne ich gerade studium auf. Aber das
/// wird auf dem dashboard in der desktop anwendung gar nicht angezeigt. das
/// sollte es aber. Auch mit buttons zum stoppen und pausieren."
///
/// Die Aevum-App meldet den Live-Zustand während einer Session alle wenigen
/// Sekunden per <c>POST /api/live</c> (Pull-Modell bleibt: NUR das Handy
/// initiiert). Antwort auf jede Meldung ist der ausstehende Befehl — so wird
/// „Stoppen"/„Pausieren" vom PC in Sekunden am Handy ausgeführt, ohne dass
/// der PC je eine Verbindung zum Handy bräuchte.
///
/// Lebenszyklus eines Befehls:
///  1. Nutzer klickt (RequestCommand) → genau EIN Befehl liegt bereit.
///  2. Jede Handy-Meldung erhält ihn als Antwort (bis TTL) — geht eine
///     Antwort verloren (Handy im Funkloch), liefert die nächste sie erneut.
///  3. Bestätigung: Die nächste Meldung zeigt den FOLGEZUSTAND (Pause →
///     isPaused=true, Stop → keine Session) → Befehl erledigt.
///  4. Nach [CommandTtlMs] ohne Bestätigung wird der Befehl verworfen —
///     das Dashboard zeigt das ehrlich als „keine Rückmeldung".
/// </summary>
public sealed class PhoneLiveRegistry
{
    /// <summary>Ohne Meldung für diese Zeit gilt der Zustand als veraltet (Steuerung aus).</summary>
    public const long StaleAfterMs = 45_000;

    /// <summary>Ein unbeantworteter Befehl wird nach dieser Zeit verworfen.</summary>
    public const long CommandTtlMs = 60_000;

    /// <summary>So lange ohne jede Meldung, bis die Handy-Karte ganz verschwindet.</summary>
    public const long GoneAfterMs = 30 * 60_000;

    private readonly object _lock = new();
    private PhoneLiveSession? _session;
    private PhoneLiveCommand? _pending;
    private long _pendingSetMs;

    /// <summary>
    /// Verarbeitet eine Meldung der App (null = „keine Session mehr").
    /// Rückgabe: der ausstehende Befehl, den die App jetzt ausführen soll.
    /// </summary>
    public PhoneLiveCommand? HandleRelay(PhoneLiveSession? session, long nowMs)
    {
        lock (_lock)
        {
            if (session is null)
            {
                // Die App meldet ausdrücklich „nichts läuft mehr".
                _session = null;
            }
            else
            {
                // Server stempelt den Empfang — die Uhr des Handys geht uns nichts an.
                _session = session with { LastUpdateMs = nowMs };
            }

            return TakePendingFor(_session, nowMs);
        }
    }

    /// <summary>„Pausieren"/„Fortsetzen"/„Stoppen" wurde im Dashboard geklickt.</summary>
    public void RequestCommand(PhoneLiveCommand command, long nowMs)
    {
        lock (_lock)
        {
            _pending = command;
            _pendingSetMs = nowMs;
        }
    }

    /// <summary>Momentaufnahme fürs Dashboard (Sekunden-Takt).</summary>
    public PhoneLiveStatus GetStatus(long nowMs)
    {
        lock (_lock)
        {
            if (_session is not null && nowMs - _session.LastUpdateMs > GoneAfterMs)
            {
                // Seit einer halben Stunde keine Meldung mehr — die Anzeige
                // wäre reine Spekulation (Handy kann längst gestoppt haben).
                _session = null;
                _pending = null;
            }
            var stale = _session is not null && nowMs - _session.LastUpdateMs > StaleAfterMs;
            return new PhoneLiveStatus(
                _session,
                stale,
                _pending,
                _pending is null ? 0 : Math.Max(0, nowMs - _pendingSetMs));
        }
    }

    /// <summary>Verwirft den ausstehenden Befehl (nur für Tests/Diagnose).</summary>
    internal void ClearPendingForTest()
    {
        lock (_lock)
        {
            _pending = null;
        }
    }

    private PhoneLiveCommand? TakePendingFor(PhoneLiveSession? session, long nowMs)
    {
        if (_pending is null)
        {
            return null;
        }
        if (nowMs - _pendingSetMs > CommandTtlMs)
        {
            _pending = null; // niemand hat es bestätigt — ehrlich aufgeben
            return null;
        }
        if (IsFulfilled(_pending.Value, session))
        {
            _pending = null; // Folgezustand angekommen → erledigt
            return null;
        }
        return _pending;
    }

    /// <summary>Zeigt der gemeldete Zustand, dass der Befehl angekommen ist?</summary>
    internal static bool IsFulfilled(PhoneLiveCommand command, PhoneLiveSession? session) => command switch
    {
        PhoneLiveCommand.Pause => session is { IsPaused: true },
        // „Fortsetzen" startet in der App eine NEUE Session (M18.62) —
        // Erfüllung ist deshalb „irgendetwas läuft wieder", nicht die ID.
        PhoneLiveCommand.Resume => session is { IsPaused: false },
        PhoneLiveCommand.Stop => session is null,
        _ => true,
    };
}
