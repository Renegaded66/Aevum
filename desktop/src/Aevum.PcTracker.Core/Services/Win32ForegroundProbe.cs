using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Runtime.Versioning;
using System.Text;
using Aevum.PcTracker.Core.Models;

namespace Aevum.PcTracker.Core.Services;

/// <summary>
/// Windows-Implementierung: Vordergrundfenster (GetForegroundWindow) und
/// Inaktivitätszeit (GetLastInputInfo). Beides sind reine Lese-APIs ohne Hooks —
/// Spiele/Anti-Cheat werden nicht berührt. Außerhalb von Windows liefert die
/// Klasse nichts (sie wird dort gar nicht erst aufgerufen).
/// </summary>
public sealed class Win32ForegroundProbe : IForegroundProbe
{
    private readonly string _ownExeName;

    public Win32ForegroundProbe()
    {
        var name = "";
        try
        {
            name = Process.GetCurrentProcess().ProcessName.ToLowerInvariant() + ".exe";
        }
        catch
        {
            // Egal — dann wird die eigene App eben nicht ausgefiltert.
        }
        _ownExeName = name;
    }

    public ForegroundSample? GetForeground()
    {
        if (!OperatingSystem.IsWindows())
        {
            return null;
        }
        try
        {
            var hwnd = GetForegroundWindow();
            if (hwnd == IntPtr.Zero)
            {
                return null;
            }
            _ = GetWindowThreadProcessId(hwnd, out var pid);
            if (pid == 0)
            {
                return null;
            }

            using var proc = Process.GetProcessById((int)pid);
            string? path = null;
            try
            {
                // Kann bei erhöhten Prozessen verweigert werden — dann nur der Name.
                path = proc.MainModule?.FileName;
            }
            catch
            {
                // access denied / Prozess inzwischen weg
            }
            var exe = !string.IsNullOrEmpty(path) ? Path.GetFileName(path) : proc.ProcessName + ".exe";
            var key = ProgramNames.KeyFor(exe);
            if (key.Length == 0)
            {
                return null;
            }
            if (_ownExeName.Length > 0 && key.Equals(_ownExeName, StringComparison.OrdinalIgnoreCase))
            {
                return null; // die eigene Oberfläche wird nie getrackt
            }
            return new ForegroundSample
            {
                ProgramKey = key,
                DisplayName = ProgramNames.DisplayNameFor(key),
                Path = path ?? "",
                WindowTitle = GetWindowTitle(hwnd),
            };
        }
        catch
        {
            return null;
        }
    }

    public long GetIdleMs()
    {
        if (!OperatingSystem.IsWindows())
        {
            return 0;
        }
        try
        {
            var info = new LastInputInfo { cbSize = (uint)Marshal.SizeOf<LastInputInfo>() };
            if (!GetLastInputInfo(ref info))
            {
                return 0;
            }
            // Beide Werte sind 32-Bit-Tick-Zähler; die Subtraktion als uint
            // trägt den Wrap-Overflow (nach ~49 Tagen Laufzeit) korrekt.
            var now = unchecked((uint)Environment.TickCount);
            var idle = unchecked(now - info.dwTime);
            return idle;
        }
        catch
        {
            return 0;
        }
    }

    [System.Runtime.Versioning.SupportedOSPlatform("windows")]
    private static string GetWindowTitle(IntPtr hwnd)
    {
        if (!OperatingSystem.IsWindows())
        {
            return "";
        }
        try
        {
            var length = GetWindowTextLength(hwnd);
            if (length <= 0)
            {
                return "";
            }
            var sb = new StringBuilder(length + 1);
            _ = GetWindowText(hwnd, sb, sb.Capacity);
            return sb.ToString();
        }
        catch
        {
            return "";
        }
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct LastInputInfo
    {
        public uint cbSize;
        public uint dwTime;
    }

    [DllImport("user32.dll")]
    [SupportedOSPlatform("windows")]
    private static extern IntPtr GetForegroundWindow();

    [DllImport("user32.dll")]
    [SupportedOSPlatform("windows")]
    private static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint lpdwProcessId);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    [SupportedOSPlatform("windows")]
    private static extern int GetWindowText(IntPtr hWnd, StringBuilder lpString, int nMaxCount);

    [DllImport("user32.dll")]
    [SupportedOSPlatform("windows")]
    private static extern int GetWindowTextLength(IntPtr hWnd);

    [DllImport("user32.dll")]
    [SupportedOSPlatform("windows")]
    private static extern bool GetLastInputInfo(ref LastInputInfo plii);
}
