using Aevum.PcTracker.Core.Models;

namespace Aevum.PcTracker.Core.Services;

/// <summary>
/// Liefert das aktuelle Vordergrundfenster und die Inaktivitätszeit.
/// Die echte Implementierung ist Windows-spezifisch
/// (<see cref="Win32ForegroundProbe"/>); Tests nutzen eine Fake-Probe.
/// </summary>
public interface IForegroundProbe
{
    /// <summary>Aktuelles Vordergrundfenster oder null (kein Fenster/eigene App).</summary>
    ForegroundSample? GetForeground();

    /// <summary>Millisekunden seit der letzten Nutzereingabe (Tastatur/Maus).</summary>
    long GetIdleMs();
}

/// <summary>
/// Probe für Nicht-Windows (und als sicherer Rückfall): liefert nie ein Fenster,
/// die App läuft weiter (UI zeigt einen Hinweis), es wird nichts aufgezeichnet.
/// </summary>
public sealed class NullForegroundProbe : IForegroundProbe
{
    public ForegroundSample? GetForeground() => null;

    public long GetIdleMs() => 0;
}
