using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Automation;

namespace Most.Windows.Views;

internal static class Theme
{
    public static readonly SolidColorBrush Background = Brush("#0B1120"), Panel = Brush("#121C30"), Input = Brush("#1C2941"),
        Text = Brush("#F1F5FA"), Muted = Brush("#9CACBF"), Accent = Brush("#68E1D4"), BorderColor = Brush("#26344C");
    public static SolidColorBrush Brush(string hex) => new((Color)ColorConverter.ConvertFromString(hex));
    public static TextBlock Label(string text, double size = 14, Brush? color = null) => new() {
        Text = text, FontSize = size, Foreground = color ?? Text, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 0, 0, 9) };
    public static Button Button(string text, bool primary = false)
    {
        var b = new Button { Content = text, Background = primary ? Accent : Input, Foreground = primary ? Background : Text,
            Padding = new Thickness(17, 11, 17, 11), Margin = new Thickness(0, 0, 9, 8), BorderThickness = new Thickness(0),
            FontSize = 14, FontWeight = FontWeights.SemiBold, Cursor = System.Windows.Input.Cursors.Hand, MinHeight = 42 };
        var border = new FrameworkElementFactory(typeof(Border)); border.SetValue(Border.CornerRadiusProperty, new CornerRadius(12));
        border.SetBinding(Border.BackgroundProperty, new System.Windows.Data.Binding("Background") { RelativeSource = new System.Windows.Data.RelativeSource(System.Windows.Data.RelativeSourceMode.TemplatedParent) });
        var presenter = new FrameworkElementFactory(typeof(ContentPresenter));
        presenter.SetValue(FrameworkElement.MarginProperty, new Thickness(17, 11, 17, 11));
        presenter.SetValue(FrameworkElement.HorizontalAlignmentProperty, HorizontalAlignment.Center);
        presenter.SetValue(FrameworkElement.VerticalAlignmentProperty, VerticalAlignment.Center); border.AppendChild(presenter);
        b.Template = new ControlTemplate(typeof(Button)) { VisualTree = border };
        b.IsEnabledChanged += (_, _) => b.Opacity = b.IsEnabled ? 1 : .45;
        AutomationProperties.SetName(b, text); return b;
    }
    public static TextBox TextInput(string value = "", bool multiline = false) => new() {
        Text = value, Background = Input, Foreground = Text, CaretBrush = Accent, BorderBrush = BorderColor,
        BorderThickness = new Thickness(1), Padding = new Thickness(12), FontSize = 14,
        AcceptsReturn = multiline, TextWrapping = multiline ? TextWrapping.Wrap : TextWrapping.NoWrap,
        VerticalScrollBarVisibility = multiline ? ScrollBarVisibility.Auto : ScrollBarVisibility.Hidden,
        Margin = new Thickness(0, 0, 0, 12) };
    public static Border Card(UIElement content) => new() { Child = content, Background = Panel,
        CornerRadius = new CornerRadius(20), Padding = new Thickness(22), BorderBrush = BorderColor,
        BorderThickness = new Thickness(1), Margin = new Thickness(0, 0, 0, 16) };
    public static CheckBox Check(string text, bool value) {
        var c = new CheckBox { Content = text, IsChecked = value, Foreground = Text, FontSize = 14,
            Margin = new Thickness(0, 5, 0, 13), VerticalContentAlignment = VerticalAlignment.Center };
        AutomationProperties.SetName(c, text); return c;
    }
    public static ComboBox Combo() => new() { MinHeight = 35, FontSize = 14, Margin = new Thickness(0, 0, 9, 10), MinWidth = 150 };
}
