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

    /// <summary>
    /// Spielt gerade Ton ab? Liest den Spitzenpegel des Standard-Ausgabegeräts
    /// (CoreAudio „IAudioMeterInformation“) — reine Lese-API ohne Aufnahme.
    /// </summary>
    public bool IsAudioPlaying()
    {
        if (!OperatingSystem.IsWindows())
        {
            return false;
        }
        EnsureAudioMeter();
        if (_audioMeter is null)
        {
            return false;
        }
        try
        {
            _audioMeter.GetPeakValue(out var peak);
            return peak > 0.005f;
        }
        catch
        {
            // Gerät wurde entfernt/gewechselt — bis zum nächsten Start ohne
            // Audio-Erkennung weiterlaufen (die Maus/Tastatur-Erkennung bleibt).
            _audioMeter = null;
            return false;
        }
    }

    private IAudioMeterInformation? _audioMeter;
    private bool _audioMeterTried;

    private void EnsureAudioMeter()
    {
        if (_audioMeterTried)
        {
            return;
        }
        _audioMeterTried = true;
        // COM-Initialisierung ist systemabhängig (kein Ausgabegerät, RDP-Sitzung,
        // Dienstkontext) und darf die Aufzeichnung nie stören: genau ein Versuch,
        // danach zählt Audio schlicht nie als „Aktivität“ — die App läuft weiter.
        try
        {
            var enumerator = (IMMDeviceEnumerator)new MMDeviceEnumeratorComObject();
            var hr = enumerator.GetDefaultAudioEndpoint(0 /* eRender */, 1 /* eMultimedia */, out var device);
            if (hr == 0 && device is not null)
            {
                var iid = typeof(IAudioMeterInformation).GUID;
                hr = device.Activate(ref iid, 23 /* CLSCTX_ALL */, IntPtr.Zero, out var obj);
                if (hr == 0)
                {
                    _audioMeter = obj as IAudioMeterInformation;
                }
            }
        }
        catch
        {
            _audioMeter = null;
        }
    }

    [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
    private class MMDeviceEnumeratorComObject
    {
    }

    [Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDeviceEnumerator
    {
        int EnumAudioEndpoints(int dataFlow, int stateMask, out IntPtr devices);
        int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice device);
        int GetDevice([MarshalAs(UnmanagedType.LPWStr)] string id, out IMMDevice device);
        int RegisterEndpointNotificationCallback(IntPtr client);
        int UnregisterEndpointNotificationCallback(IntPtr client);
    }

    [Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDevice
    {
        int Activate(ref Guid iid, int clsCtx, IntPtr activationParams,
            [MarshalAs(UnmanagedType.IUnknown)] out object interfacePointer);
        int OpenPropertyStore(int stgmAccess, out IntPtr properties);
        int GetId([MarshalAs(UnmanagedType.LPWStr)] out string id);
        int GetState(out int state);
    }

    [Guid("C02216F6-8C67-4B5B-9D00-D008E73E0064"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IAudioMeterInformation
    {
        int GetPeakValue(out float peak);
        int GetMeteringChannelCount(out int channelCount);
        int GetChannelsPeakValues(int channelCount, [Out] float[] peakValues);
        int QueryHardwareSupport(out int hardwareSupportMask);
    }

    /// <summary>
    /// Läuft durch die Prozessliste und sammelt Programme mit sichtbarem
    /// Hauptfenster (Fenstertitel nicht leer) — dieselbe Menge, die auch als
    /// Vordergrundprogramm in Frage kommt. Läuft außerhalb von Windows leer.
    /// </summary>
    public IReadOnlyList<RunningProgram> GetRunningPrograms()
    {
        if (!OperatingSystem.IsWindows())
        {
            return Array.Empty<RunningProgram>();
        }
        var list = new List<RunningProgram>();
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var proc in Process.GetProcesses())
        {
            try
            {
                if (proc.MainWindowHandle == IntPtr.Zero)
                {
                    continue;
                }
                var title = proc.MainWindowTitle;
                if (string.IsNullOrWhiteSpace(title))
                {
                    continue;
                }
                var key = ProgramNames.KeyFor(proc.ProcessName.ToLowerInvariant() + ".exe");
                if (key.Length == 0 || !seen.Add(key))
                {
                    continue;
                }
                if (_ownExeName.Length > 0 && key.Equals(_ownExeName, StringComparison.OrdinalIgnoreCase))
                {
                    continue; // die eigene Oberfläche zählt nicht
                }
                list.Add(new RunningProgram
                {
                    ProgramKey = key,
                    Name = ProgramNames.DisplayNameFor(key),
                    Title = title,
                });
            }
            catch
            {
                // Prozess inzwischen beendet oder kein Zugriff — überspringen.
            }
            finally
            {
                proc.Dispose();
            }
        }
        return list
            .OrderBy(p => p.Name, StringComparer.OrdinalIgnoreCase)
            .ThenBy(p => p.ProgramKey, StringComparer.Ordinal)
            .ToList();
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
