using System.ComponentModel;
using Avalonia.Controls;
using Avalonia.Threading;
using Aevum.PcTracker.ViewModels;

namespace Aevum.PcTracker.Views;

public partial class RulesView : UserControl
{
    private RulesViewModel? _subscribed;

    public RulesView()
    {
        InitializeComponent();
        DataContextChanged += OnDataContextChanged;
    }

    private void OnDataContextChanged(object? sender, EventArgs e)
    {
        if (_subscribed is not null)
        {
            _subscribed.PropertyChanged -= OnViewModelPropertyChanged;
        }
        _subscribed = DataContext as RulesViewModel;
        if (_subscribed is not null)
        {
            _subscribed.PropertyChanged += OnViewModelPropertyChanged;
        }
    }

    // Beim Öffnen des Entwurfs dorthin scrollen — sonst liegt die Box außerhalb des Blicks.
    private void OnViewModelPropertyChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName == nameof(RulesViewModel.IsDraftOpen) && sender is RulesViewModel { IsDraftOpen: true })
        {
            Dispatcher.UIThread.Post(() => DraftBorder.BringIntoView(), DispatcherPriority.Background);
        }
    }

    // Explizite Übernahme der ComboBox-Auswahl in das ViewModel — unabhängig vom
    // Binding-Mechanismus, damit gespeichert wird, was sichtbar ausgewählt ist.
    private void OnActivityChanged(object? sender, SelectionChangedEventArgs e)
    {
        if (DataContext is RulesViewModel vm && sender is ComboBox combo)
        {
            vm.SetDraftActivity(combo.SelectedItem as ActivityOptionViewModel);
        }
    }

    private void OnThresholdChanged(object? sender, SelectionChangedEventArgs e)
    {
        if (DataContext is RulesViewModel vm && sender is ComboBox combo)
        {
            vm.SetDraftThreshold(combo.SelectedItem as ThresholdOptionViewModel);
        }
    }

    private void OnModeChanged(object? sender, SelectionChangedEventArgs e)
    {
        if (DataContext is RulesViewModel vm && sender is ComboBox combo)
        {
            vm.SetDraftMode(combo.SelectedItem as ModeOptionViewModel);
        }
    }
}
