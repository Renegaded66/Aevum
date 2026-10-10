namespace Aevum.PcTracker.Core.Services;

/// <summary>Zeitzonen- und Zeit-Helfer für die Weitergabe an Aevum (IANA-IDs).</summary>
public static class TimeHelper
{
    /// <summary>
    /// Lokale Zeitzone als IANA-ID (z. B. "Europe/Berlin"). Windows liefert
    /// nativ Windows-IDs ("W. Europe Standard Time") — die werden übersetzt,
    /// weil Aevum IANA erwartet.
    /// </summary>
    public static string LocalIanaTimeZoneId()
    {
        try
        {
            var local = TimeZoneInfo.Local.Id;
            if (OperatingSystem.IsWindows()
                && TimeZoneInfo.TryConvertWindowsIdToIanaId(local, out var iana)
                && !string.IsNullOrWhiteSpace(iana))
            {
                return iana;
            }
            return local;
        }
        catch
        {
            return "UTC";
        }
    }
}
