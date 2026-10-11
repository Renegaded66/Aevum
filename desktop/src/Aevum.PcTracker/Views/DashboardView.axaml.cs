using Avalonia.Controls;
using Aevum.PcTracker.ViewModels;

namespace Aevum.PcTracker.Views;

public partial class DashboardView : UserControl
{
    public DashboardView()
    {
        InitializeComponent();
    }

    // Explizite Übernahme der ComboBox-Auswahl in das ViewModel — unabhängig vom
    // Binding-Mechanismus, damit gespeichert wird, was sichtbar ausgewählt ist
    // (gleiches Muster wie bei den Regel-Dropdowns).
    private void OnGeneralActivityChanged(object? sender, SelectionChangedEventArgs e)
    {
        if (DataContext is DashboardViewModel vm && sender is ComboBox combo)
        {
            vm.SetGeneralActivity(combo.SelectedItem as GeneralActivityOption);
        }
    }
}
