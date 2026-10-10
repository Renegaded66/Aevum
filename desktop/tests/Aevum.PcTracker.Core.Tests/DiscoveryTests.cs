using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using Aevum.PcTracker.Core.Services;
using Xunit;

namespace Aevum.PcTracker.Core.Tests;

public class DiscoveryTests
{
    private static readonly AnnouncePayload Info = new("dev-1", "TEST-PC", 47825, true);

    private static byte[] Discover(string magic = DiscoveryResponder.DiscoverMagic, int version = 1)
        => Encoding.UTF8.GetBytes($"{{\"magic\":\"{magic}\",\"v\":{version},\"replyPort\":0}}");

    [Fact]
    public void BuildReply_ValidDiscover_ProducesAnnounce()
    {
        var reply = DiscoveryResponder.BuildReply(Discover(), Info);

        Assert.NotNull(reply);
        var root = JsonDocument.Parse(reply!).RootElement;
        Assert.Equal(DiscoveryResponder.AnnounceMagic, root.GetProperty("magic").GetString());
        Assert.Equal(1, root.GetProperty("v").GetInt32());
        Assert.Equal("dev-1", root.GetProperty("deviceId").GetString());
        Assert.Equal("TEST-PC", root.GetProperty("name").GetString());
        Assert.Equal(47825, root.GetProperty("apiPort").GetInt32());
        Assert.True(root.GetProperty("pairingActive").GetBoolean());
        // Keine Geheimnisse in der Antwort.
        Assert.DoesNotContain("token", Encoding.UTF8.GetString(reply), StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public void BuildReply_RejectsForeignAndBrokenDatagrams()
    {
        Assert.Null(DiscoveryResponder.BuildReply(Discover(magic: "SOMETHING-ELSE"), Info));
        Assert.Null(DiscoveryResponder.BuildReply(Discover(version: 99), Info));
        Assert.Null(DiscoveryResponder.BuildReply(Encoding.UTF8.GetBytes("nicht json"), Info));
        Assert.Null(DiscoveryResponder.BuildReply(ReadOnlySpan<byte>.Empty, Info));
        Assert.Null(DiscoveryResponder.BuildReply(new byte[DiscoveryResponder.MaxDatagramBytes + 1], Info));
        Assert.Null(DiscoveryResponder.BuildReply(Encoding.UTF8.GetBytes("[1,2,3]"), Info));
    }

    [Fact]
    public async Task Live_UdpLoopback_AnswersWithinTimeout()
    {
        // Freien UDP-Port ermitteln.
        using var probe = new UdpClient(new IPEndPoint(IPAddress.Loopback, 0));
        var port = ((IPEndPoint)probe.Client.LocalEndPoint!).Port;
        probe.Dispose();

        using var responder = new DiscoveryResponder(port, () => Info);
        responder.Start();

        using var client = new UdpClient();
        await client.SendAsync(Discover(), new IPEndPoint(IPAddress.Loopback, port));

        var receive = client.ReceiveAsync();
        var finished = await Task.WhenAny(receive, Task.Delay(5_000));
        Assert.True(finished == receive, "Keine Discovery-Antwort innerhalb von 5 Sekunden.");
        var result = await receive;

        var root = JsonDocument.Parse(result.Buffer).RootElement;
        Assert.Equal(DiscoveryResponder.AnnounceMagic, root.GetProperty("magic").GetString());
        Assert.Equal("TEST-PC", root.GetProperty("name").GetString());
        // Die Antwort kam per Unicast direkt vom Responder-Port zurück.
        Assert.Equal(port, result.RemoteEndPoint.Port);
    }
}
