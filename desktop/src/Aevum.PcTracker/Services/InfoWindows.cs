using Avalonia;
using Avalonia.Controls;
using Avalonia.Layout;
using Avalonia.Media;

namespace Aevum.PcTracker.Services;

/// <summary>Kleine Hinweis-Fenster (statt stummem Beenden oder rohem Crash).</summary>
public static class InfoWindows
{
    public static Window Create(string title, string message)
    {
        var okButton = new Button
        {
            Content = "OK",
            HorizontalAlignment = HorizontalAlignment.Center,
            Classes = { "primary" },
        };
        var window = new Window
        {
            Title = title,
            Width = 460,
            SizeToContent = SizeToContent.Height,
            CanResize = false,
            WindowStartupLocation = WindowStartupLocation.CenterScreen,
            Background = new SolidColorBrush(Color.Parse("#090B16")),
            Content = new StackPanel
            {
                Margin = new Thickness(28),
                Spacing = 18,
                Children =
                {
                    new TextBlock
                    {
                        Text = title,
                        FontSize = 18,
                        FontWeight = FontWeight.SemiBold,
                        Foreground = new SolidColorBrush(Color.Parse("#F0EFF8")),
                        TextWrapping = TextWrapping.Wrap,
                    },
                    new TextBlock
                    {
                        Text = message,
                        Foreground = new SolidColorBrush(Color.Parse("#CAC8D8")),
                        TextWrapping = TextWrapping.Wrap,
                        LineHeight = 22,
                    },
                    okButton,
                },
            },
        };
        okButton.Click += (_, _) => window.Close();
        return window;
    }
}
