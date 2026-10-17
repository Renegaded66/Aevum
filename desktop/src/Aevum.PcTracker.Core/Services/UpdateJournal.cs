namespace Aevum.PcTracker.Core.Services;

/// <summary>
/// PC-Tracker 1.5.1: Das Update-Tagebuch.
///
/// Devon-Befund: „In 1.4.1 hab ich auf update herunterladen geklickt […] dann
/// nach ein paar sekunden hat sich einfach wieder 1.4.1 geöffnet. Im ordner
/// wo die exe ausgeführt wird habe ich auch keine neuen dateien gefunden."
/// Zwei Versuche, beide Male das Gleiche — und der alte Austausch-Mechanismus
/// verschluckte ALLE Fehler still (>nul), sodass niemand sagen konnte, wo es
/// hing. Deshalb:
///
///  1. <see cref="UpdateLog"/> — jede Update-Station schreibt in
///     <c>%APPDATA%\AevumPcTracker\update.log</c> (App-Seite: Download,
///     Entpacken, Skriptstart; Swap-Seite: Warten, Kopieren, Verifizieren,
///     Start). Dieser Log bleibt liegen und erzählt die Wahrheit.
///  2. <see cref="PendingUpdateStore"/> — vor dem Neustart wird vermerkt,
///     auf welche Version aktualisiert werden sollte. Beim nächsten Start
///     kann die App selbst feststellen: Erfolg (neue Version läuft) oder
///     Fehlschlag (noch die alte) — und den Fehlschlag ehrlich anzeigen,
///     statt still wieder „1.4.1" zu sein.
/// </summary>
public static class UpdateLog
{
    public static string PathFor(string dataDirectory) =>
        Path.Combine(dataDirectory, "update.log");

    /// <summary>Schreibt eine Zeile (Zeitstempel + Text). Fehler werden bewusst geschluckt — Logging darf nie etwas kaputt machen.</summary>
    public static void Append(string dataDirectory, string message)
    {
        try
        {
            File.AppendAllText(
                PathFor(dataDirectory),
                $"{DateTime.Now:yyyy-MM-dd HH:mm:ss} {message}{Environment.NewLine}");
        }
        catch
        {
            // Logging ist Best-Effort.
        }
    }
}

/// <summary>Der gemerkter Update-Auftrag (Ziel + Startzeitpunkt des Austauschs).</summary>
public sealed record PendingUpdate(Version Target, long StartedAtMs);

/// <summary>
/// Legt VOR dem Austausch ab, dass ein Update auf Version X läuft — und beim
/// nächsten Start kann die App daraus ableiten: geklappt oder nicht.
/// Bewusst eine einfache Textdatei (robust, von Hand lesbar).
/// </summary>
public static class PendingUpdateStore
{
    public static string PathFor(string dataDirectory) =>
        Path.Combine(dataDirectory, "pending-update.txt");

    public static void Write(string dataDirectory, Version target, long startedAtMs)
    {
        try
        {
            File.WriteAllText(PathFor(dataDirectory), $"{target}|{startedAtMs}");
        }
        catch
        {
            // Best-Effort — ohne Marker fehlt nur die Nachkontrolle.
        }
    }

    public static PendingUpdate? Read(string dataDirectory)
    {
        try
        {
            var path = PathFor(dataDirectory);
            if (!File.Exists(path))
            {
                return null;
            }
            var parts = File.ReadAllText(path).Split('|');
            if (parts.Length != 2 || !Version.TryParse(parts[0], out var target)
                || !long.TryParse(parts[1], out var startedAt))
            {
                return null;
            }
            return new PendingUpdate(target, startedAt);
        }
        catch
        {
            return null;
        }
    }

    public static void Clear(string dataDirectory)
    {
        try
        {
            var path = PathFor(dataDirectory);
            if (File.Exists(path))
            {
                File.Delete(path);
            }
        }
        catch
        {
            // Best-Effort.
        }
    }

    /// <summary>
    /// Was ist aus dem gemerkten Update geworden?
    /// None = kein Auftrag; Succeeded = die neue Version läuft; Failed = es
    /// läuft noch die alte — der Austausch ist nicht durchgekommen.
    /// </summary>
    public static PendingUpdateOutcome Classify(PendingUpdate? pending, Version current)
    {
        if (pending is null)
        {
            return PendingUpdateOutcome.None;
        }
        return current >= pending.Target
            ? PendingUpdateOutcome.Succeeded
            : PendingUpdateOutcome.Failed;
    }
}

public enum PendingUpdateOutcome
{
    None,
    Succeeded,
    Failed,
}
