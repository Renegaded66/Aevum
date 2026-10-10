namespace Aevum.PcTracker.Services;

/// <summary>
/// Verhindert, dass die App zweimal läuft (zwei Tracker auf derselben Datenbank
/// wären ein Datenrennen). Zweite Instanzen zeigen einen Hinweis und beenden sich.
/// </summary>
public static class SingleInstance
{
    private static Mutex? _mutex;

    public static bool TryAcquire()
    {
        _mutex = new Mutex(initiallyOwned: true, name: "AevumPcTracker.SingleInstance.v1", out var createdNew);
        if (!createdNew)
        {
            _mutex.Dispose();
            _mutex = null;
        }
        return createdNew;
    }
}
