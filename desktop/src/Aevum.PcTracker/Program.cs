using Avalonia;

namespace Aevum.PcTracker;

internal static class Program
{
    // Das Projekt ist als WinExe gebaut: Auf Windows startet die App ohne
    // Konsolenfenster ("bestenfalls nur mit ui ohne shell").
    [STAThread]
    public static void Main(string[] args)
    {
        BuildAvaloniaApp().StartWithClassicDesktopLifetime(args);
    }

    public static AppBuilder BuildAvaloniaApp()
        => AppBuilder.Configure<App>()
            .UsePlatformDetect()
            .WithInterFont()
            .LogToTrace();
}
