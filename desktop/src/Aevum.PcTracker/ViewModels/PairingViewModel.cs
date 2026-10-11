using Avalonia.Media;
using Avalonia.Threading;
using Aevum.PcTracker.Core.Services;
using Aevum.PcTracker.Localization;
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
    [ObservableProperty] private bool _isConnected;

    public PairingViewModel(AppHost host)
    {
        _host = host;
        _host.Server.ClientAccessed += () => Dispatcher.UIThread.Post(Refresh);
        Loc.LanguageChanged += () => Dispatcher.UIThread.Post(Refresh);
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
                ? Loc.F("s.pair_no_lan", port)
                : string.Join("   ", addresses.Select(a => $"{a}:{port}"));
            ServerStatusText = Loc.S("s.pair_server_ready");
            ServerDotBrush = new SolidColorBrush(Color.Parse("#5EEAD4"));
        }
        else
        {
            AddressText = "";
            ServerStatusText = _host.ServerError is { Length: > 0 } error
                ? Loc.F("s.dash_server_error", error)
                : Loc.S("s.dash_server_starting");
            ServerDotBrush = new SolidColorBrush(Color.Parse("#FFC85C"));
        }

        var now = _host.Clock.NowMs;
        var last = server.LastClientAccessMs;
        IsConnected = last > 0 && now - last < 10 * 60_000L;
        if (last == 0)
        {
            LastAccessText = Loc.S("s.pair_no_fetch");
        }
        else
        {
            var name = server.LastClientName;
            var ago = UiFormat.Ago(last, now);
            LastAccessText = Loc.F("s.pair_last_fetch", name.Length > 0 ? $"{ago} — {name}" : ago);
        }
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
            ResetHintText = Loc.S("s.pair_reset_confirm");
            return;
        }
        _host.Tokens.Rotate();
        ResetConfirmVisible = false;
        ResetHintText = Loc.S("s.pair_reset_done");
        Refresh();
    }

    [RelayCommand]
    private void CancelReset()
    {
        ResetConfirmVisible = false;
        ResetHintText = "";
    }
}
