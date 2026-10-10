using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;

namespace Aevum.PcTracker.Core.Services;

/// <summary>Was der PC im Netz über sich bekannt gibt (keine Geheimnisse).</summary>
public sealed record AnnouncePayload(string DeviceId, string Name, int ApiPort, bool PairingActive);

/// <summary>
/// UDP-Discovery: die Aevum-App sucht den PC per Broadcast (Port 47826), jeder
/// Tracker antwortet per Unicast. So muss der Nutzer nichts abtippen.
/// Details: docs/PC-TRACKER-PROTOCOL.md, Abschnitt 2.
/// </summary>
public sealed class DiscoveryResponder : IDisposable
{
    public const string DiscoverMagic = "AEVUM-PC-DISCOVER";
    public const string AnnounceMagic = "AEVUM-PC-ANNOUNCE";
    public const int ProtocolVersion = 1;
    public const int MaxDatagramBytes = 512;

    private readonly int _port;
    private readonly Func<AnnouncePayload> _announce;

    private UdpClient? _udp;
    private CancellationTokenSource? _cts;
    private Task? _loop;

    public DiscoveryResponder(int port, Func<AnnouncePayload> announce)
    {
        _port = port;
        _announce = announce;
    }

    public bool IsRunning => _udp is not null;

    public void Start()
    {
        if (_udp is not null)
        {
            return;
        }
        _udp = new UdpClient(new IPEndPoint(IPAddress.Any, _port))
        {
            EnableBroadcast = true,
        };
        _cts = new CancellationTokenSource();
        _loop = Task.Run(() => ReceiveLoopAsync(_cts.Token));
    }

    private async Task ReceiveLoopAsync(CancellationToken ct)
    {
        var udp = _udp;
        if (udp is null)
        {
            return;
        }
        while (!ct.IsCancellationRequested)
        {
            try
            {
                var received = await udp.ReceiveAsync(ct);
                var reply = BuildReply(received.Buffer, _announce());
                if (reply is not null)
                {
                    try
                    {
                        await udp.SendAsync(reply, received.RemoteEndPoint, ct);
                    }
                    catch
                    {
                        // Antwort konnte nicht zugestellt werden — Anfrage verpufft, ok.
                    }
                }
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (ObjectDisposedException)
            {
                break;
            }
            catch (SocketException)
            {
                // Kurzer Netzfehler — Schleife läuft weiter.
            }
        }
    }

    /// <summary>
    /// Reine Funktion (testbar): gültige Suchanfrage → Antwort-Bytes, sonst null.
    /// </summary>
    public static byte[]? BuildReply(ReadOnlySpan<byte> request, AnnouncePayload info)
    {
        if (request.Length == 0 || request.Length > MaxDatagramBytes)
        {
            return null;
        }
        try
        {
            var doc = JsonDocument.Parse(request.ToArray());
            var root = doc.RootElement;
            if (root.ValueKind != JsonValueKind.Object)
            {
                return null;
            }
            if (!root.TryGetProperty("magic", out var magic)
                || !string.Equals(magic.GetString(), DiscoverMagic, StringComparison.Ordinal))
            {
                return null;
            }
            if (root.TryGetProperty("v", out var version)
                && version.ValueKind == JsonValueKind.Number
                && version.GetInt32() != ProtocolVersion)
            {
                return null;
            }
            var payload = JsonSerializer.Serialize(new
            {
                magic = AnnounceMagic,
                v = ProtocolVersion,
                deviceId = info.DeviceId,
                name = info.Name,
                apiPort = info.ApiPort,
                pairingActive = info.PairingActive,
            });
            return Encoding.UTF8.GetBytes(payload);
        }
        catch (JsonException)
        {
            return null;
        }
    }

    public void Stop()
    {
        try
        {
            _cts?.Cancel();
            _udp?.Dispose();
        }
        catch
        {
            // Beim Beenden egal.
        }
        _udp = null;
        _cts = null;
    }

    public void Dispose() => Stop();
}
