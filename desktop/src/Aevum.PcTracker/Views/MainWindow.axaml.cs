using Avalonia.Controls;

namespace Aevum.PcTracker.Views;

public partial class MainWindow : Window
{
    /// <summary>Im Shutdown-Pfad true — dann darf das Fenster wirklich schließen.</summary>
    public bool AllowClose { get; set; }

    public MainWindow()
    {
        InitializeComponent();
    }

    protected override void OnClosing(WindowClosingEventArgs e)
    {
        // Unter Windows versteckt das X die App in den Infobereich (beenden über
        // das Tray-Menü); auf anderen Systemen schließt X ganz normal.
        if (!AllowClose && OperatingSystem.IsWindows())
        {
            e.Cancel = true;
            Hide();
            return;
        }
        base.OnClosing(e);
    }
}
