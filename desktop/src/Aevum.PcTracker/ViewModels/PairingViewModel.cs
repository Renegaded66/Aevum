using Avalonia.Media;
using Avalonia.Threading;
using Aevum.PcTracker.Core.Services;
using Aevum.PcTracker.Services;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;

namespace Aevum.PcTracker.ViewModels;

public partial class PairingViewModel : ObservableObject, IRefreshable
{
    private readonly AppHost _host;

    [ObservableProperty] private bool _pairingActive;
    [ObservableProperty] private string _codeText = "— — — — — —";
    [ObservableProperty] private string _addressText = "";
    [ObservableProperty] private string _serverStatusText = "";
    [ObservableProperty] private IBrush _serverDotBrush = new SolidColorBrush(Color.Parse("#9491A5"));
    [ObservableProperty] private string _lastAccessText = "";
    [ObservableProperty] private string _resetHintText = "";
    [ObservableProperty] private bool _resetConfirmVisible;

    public PairingViewModel(AppHost host)
    {
        _host = host;
        _host.Server.ClientAccessed += () => Dispatcher.UIThread.Post(Refresh);
        Refresh();
    }

    public void Refresh()
    {
        PairingActive = _host.Pairing.Enabled;
        CodeText = PairingActive && _host.Pairing.CurrentCode.Length == 6
            ? $"{_host.Pairing.CurrentCode[..3]} {_host.Pairing.CurrentCode[3..]}"
            : "— — — — — —";

        var server = _host.Server;
        if (server.IsRunning)
        {
            var addresses = NetworkInfo.LocalIPv4Addresses();
            var port = server.BoundPort;
            AddressText = addresses.Count == 0
                ? $"Port {port} (keine Netzwerkadresse gefunden — ist der PC im WLAN?)"
                : string.Join("   ", addresses.Select(a => $"{a}:{port}"));
            ServerStatusText = "Bereit für Abrufe aus dem Heimnetz";
            ServerDotBrush = new SolidColorBrush(Color.Parse("#5EEAD4"));
        }
        else
        {
            AddressText = "";
            ServerStatusText = _host.ServerError is { Length: > 0 } error
                ? $"LAN-Server konnte nicht starten: {error}"
                : "LAN-Server startet…";
            ServerDotBrush = new SolidColorBrush(Color.Parse("#FFC85C"));
        }

        var last = server.LastClientAccessMs;
        LastAccessText = last == 0
            ? "Bisher hat noch kein Handy Daten abgeholt."
            : $"Zuletzt abgeholt: {UiFormat.Ago(last, _host.Clock.NowMs)}";
    }

    public void OnHostStarted() => Refresh();

    partial void OnPairingActiveChanged(bool value)
    {
        if (value)
        {
            _host.Pairing.Enable();
        }
        else
        {
            _host.Pairing.Disable();
        }
        CodeText = value && _host.Pairing.CurrentCode.Length == 6
            ? $"{_host.Pairing.CurrentCode[..3]} {_host.Pairing.CurrentCode[3..]}"
            : "— — — — — —";
    }

    [RelayCommand]
    private void RotateCode()
    {
        if (_host.Pairing.Enabled)
        {
            _host.Pairing.RotateCode();
            Refresh();
        }
    }

    [RelayCommand]
    private void ResetAccess()
    {
        if (!ResetConfirmVisible)
        {
            ResetConfirmVisible = true;
            ResetHintText = "Wirklich zurücksetzen? Jedes gekoppelte Handy muss danach neu gekoppelt werden.";
            return;
        }
        _host.Tokens.Rotate();
        ResetConfirmVisible = false;
        ResetHintText = "Zugang zurückgesetzt — neue Kopplung nötig.";
        Refresh();
    }

    [RelayCommand]
    private void CancelReset()
    {
        ResetConfirmVisible = false;
        ResetHintText = "";
    }
}
