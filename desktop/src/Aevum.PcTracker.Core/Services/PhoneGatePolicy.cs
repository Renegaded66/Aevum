namespace Aevum.PcTracker.Core.Services;

/// <summary>
/// PC 1.7.1: Wann ist das Verifikations-Gate scharf?
///
/// Devon-Befund nach dem Update auf 1.6.0: „direkt nach dem update ohne zu
/// warten augenblicklich ‚Digital' angefangen […] erst nach 2 Sekunden
/// gemerkt, dass das handy was aufzeichnet." Ursache: Das Gate galt erst als
/// aktiv, nachdem ein Handy den PC einmal kontaktiert hatte
/// (`PhoneKnownAtMs`). Eine Bestandsinstallation, die aus einer Version VOR
/// 1.6 aktualisiert wurde, hatte dieses Feld noch nie — also zeichnete der PC
/// in den ersten Sekunden drauflos, bis der erste Handy-Stand eintraf.
///
/// Regel:
///  - Handy schon einmal gesehen → Gate immer scharf (fail-closed wie 1.6).
///  - Sonst Bestandsinstallation (älter als 24 h) → Schonfrist von 60 s nach
///    dem Start, in der „nicht verifiziert" gilt. Trifft der erste
///    Handy-Stand ein, wird normal bewertet; kommt keiner, zeichnet der PC
///    nach der Schonfrist wie immer auf (PC ohne Handy).
///  - Frische Installation (&lt; 24 h) → keine Schonfrist; ein nigelnagelneuer
///    PC ohne Handy soll sofort aufzeichnen dürfen.
/// </summary>
public static class PhoneGatePolicy
{
    /// <summary>Wartezeit nach dem Start für den ersten Handy-Stand.</summary>
    public const long StartupGraceMs = 60_000;

    /// <summary>Ab diesem Alter gilt eine Installation als „Bestand" (Upgrade-Fall).</summary>
    public const long UpgradeAgeThresholdMs = 24 * 60 * 60 * 1000L;

    /// <summary>Soll das Gate (auch ohne bisherigen Handy-Kontakt) scharf sein?</summary>
    public static bool IsArmed(bool phoneKnown, long installationAgeMs, long sinceStartMs)
    {
        if (phoneKnown)
        {
            return true;
        }
        return installationAgeMs >= UpgradeAgeThresholdMs && sinceStartMs < StartupGraceMs;
    }
}
