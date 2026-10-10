using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace Aevum.PcTracker.Core.Services;

/// <summary>Lokale IPv4-Adressen für die Kopplungs-Anzeige („Verbinde dich mit 192.168.…").</summary>
public static class NetworkInfo
{
    public static List<string> LocalIPv4Addresses()
    {
        var result = new List<string>();
        try
        {
            foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (ni.OperationalStatus != OperationalStatus.Up)
                {
                    continue;
                }
                if (ni.NetworkInterfaceType == NetworkInterfaceType.Loopback)
                {
                    continue;
                }
                try
                {
                    foreach (var ua in ni.GetIPProperties().UnicastAddresses)
                    {
                        if (ua.Address.AddressFamily == AddressFamily.InterNetwork
                            && !IPAddress.IsLoopback(ua.Address))
                        {
                            result.Add(ua.Address.ToString());
                        }
                    }
                }
                catch
                {
                    // Einzelne Interfaces können unter Windows werfen — überspringen.
                }
            }
        }
        catch
        {
            // Keine Netzwerkinfos verfügbar → leere Liste (UI zeigt dann nichts an).
        }
        return result.Distinct().ToList();
    }
}
