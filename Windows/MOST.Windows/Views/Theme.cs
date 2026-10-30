using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Data;
using System.Windows.Media;

namespace Most.Windows.Views;

internal static class Theme
{
    public static readonly SolidColorBrush Background = Brush("#090F1B"), Panel = Brush("#111C2D"), Input = Brush("#1A2940"),
        Text = Brush("#F0F6FC"), Muted = Brush("#8E9EB5"), Accent = Brush("#6DE1D0"), BorderColor = Brush("#24364B"),
        AccentSoft = Brush("#163B3D"), Warning = Brush("#F2C879");
    public static SolidColorBrush Brush(string hex) => new((Color)ColorConverter.ConvertFromString(hex));
    public static TextBlock Label(string text, double size = 14, Brush? color = null) => new() {
        Text = text, FontSize = size, Foreground = color ?? Text, TextWrapping = TextWrapping.Wrap,
        LineHeight = size * 1.45, Margin = new Thickness(0, 0, 0, 8) };
    public static Button Button(string text, bool primary = false)
    {
        var b = new Button { Content = text, Background = primary ? Accent : Input, Foreground = primary ? Background : Text,
            Padding = new Thickness(16, 11, 16, 11), Margin = new Thickness(0, 0, 8, 8), BorderThickness = new Thickness(1),
            BorderBrush = Brushes.Transparent, FontSize = 13, FontWeight = FontWeights.SemiBold,
            Cursor = System.Windows.Input.Cursors.Hand, MinHeight = 40 };
        b.Template = RoundedTemplate(12);
        b.IsEnabledChanged += (_, _) => b.Opacity = b.IsEnabled ? 1 : .4;
        AutomationProperties.SetName(b, text); return b;
    }
    public static ControlTemplate RoundedTemplate(double radius)
    {
        var border = new FrameworkElementFactory(typeof(Border)); border.Name = "surface";
        border.SetValue(Border.CornerRadiusProperty, new CornerRadius(radius));
        foreach (var property in new[] { Border.BackgroundProperty, Border.BorderBrushProperty, Border.BorderThicknessProperty })
            border.SetBinding(property, new Binding(property.Name) { RelativeSource = RelativeSource.TemplatedParent });
        var presenter = new FrameworkElementFactory(typeof(ContentPresenter));
        presenter.SetBinding(FrameworkElement.MarginProperty, new Binding("Padding") { RelativeSource = RelativeSource.TemplatedParent });
        presenter.SetBinding(FrameworkElement.HorizontalAlignmentProperty, new Binding("HorizontalContentAlignment") { RelativeSource = RelativeSource.TemplatedParent });
        presenter.SetValue(FrameworkElement.VerticalAlignmentProperty, VerticalAlignment.Center); border.AppendChild(presenter);
        var template = new ControlTemplate(typeof(Button)) { VisualTree = border };
        var hover = new Trigger { Property = UIElement.IsMouseOverProperty, Value = true };
        hover.Setters.Add(new Setter(UIElement.OpacityProperty, .82)); template.Triggers.Add(hover);
        var focus = new Trigger { Property = UIElement.IsKeyboardFocusedProperty, Value = true };
        focus.Setters.Add(new Setter(Border.BorderBrushProperty, Accent, "surface")); template.Triggers.Add(focus);
        return template;
    }
    public static Button Nav(string text, string glyph)
    {
        var b = Button(text); b.Background = Brushes.Transparent; b.HorizontalContentAlignment = HorizontalAlignment.Left;
        b.Padding = new Thickness(14, 12, 14, 12); b.Margin = new Thickness(0, 2, 0, 3);
        var row = new StackPanel { Orientation = Orientation.Horizontal };
        row.Children.Add(new TextBlock { Text = glyph, FontFamily = new FontFamily("Segoe MDL2 Assets"), FontSize = 17, Width = 31, VerticalAlignment = VerticalAlignment.Center });
        row.Children.Add(new TextBlock { Text = text, VerticalAlignment = VerticalAlignment.Center }); b.Content = row;
        return b;
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
    public static Border Pill(string value, bool accent = false) => new() {
        Child = new TextBlock { Text = value, FontSize = 12, FontWeight = FontWeights.SemiBold, Foreground = accent ? Accent : Muted },
        Background = accent ? AccentSoft : Input, CornerRadius = new CornerRadius(7), Padding = new Thickness(9, 5, 9, 5),
        HorizontalAlignment = HorizontalAlignment.Left, Margin = new Thickness(0, 0, 8, 10) };
    public static CheckBox Check(string text, bool value) {
        var c = new CheckBox { Content = new TextBlock { Text = text, TextWrapping = TextWrapping.Wrap }, IsChecked = value,
            Foreground = Text, FontSize = 13, Margin = new Thickness(0, 6, 0, 13), VerticalContentAlignment = VerticalAlignment.Center };
        AutomationProperties.SetName(c, text); return c;
    }
    public static ComboBox Combo() => new() { MinHeight = 36, FontSize = 13, Margin = new Thickness(0, 0, 8, 10), MinWidth = 170 };
    public static Grid AdaptiveColumns(FrameworkElement left, FrameworkElement right)
    {
        var grid = new Grid(); grid.Children.Add(left); grid.Children.Add(right);
        bool? wide = null;
        void Arrange(double width) {
            var next = width >= 780;
            if (wide == next) return; wide = next;
            grid.ColumnDefinitions.Clear(); grid.RowDefinitions.Clear();
            if (next) {
                grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1.08, GridUnitType.Star) });
                grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(16) });
                grid.ColumnDefinitions.Add(new ColumnDefinition());
                Grid.SetRow(right, 0); Grid.SetColumn(right, 2);
            } else {
                grid.ColumnDefinitions.Add(new ColumnDefinition());
                grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
                grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
                Grid.SetRow(right, 1); Grid.SetColumn(right, 0);
            }
        }
        grid.SizeChanged += (_, e) => Arrange(e.NewSize.Width); Arrange(0); return grid;
    }
    public static Grid Columns(UIElement left, UIElement right, double leftWeight = 1, double rightWeight = 1)
    {
        var grid = new Grid(); grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(leftWeight, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(16) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(rightWeight, GridUnitType.Star) });
        grid.Children.Add(left); Grid.SetColumn(right, 2); grid.Children.Add(right); return grid;
    }
}
