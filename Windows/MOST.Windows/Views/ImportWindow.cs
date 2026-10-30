using System;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using Most.Windows.Core;

namespace Most.Windows.Views;

/// Owns the import form lifetime. Failed attempts retain the same controls and draft.
public sealed class ImportWindow : Window
{
    private readonly CancellationTokenSource _lifetime;
    private CancellationTokenSource? _preview;
    private readonly Func<string, string, bool, CancellationToken, Task> _import;
    private readonly TextBox _input = Theme.TextInput("", true), _name = Theme.TextInput();
    private readonly TextBlock _feedback = Theme.Label("", 13, Theme.Muted), _error = Theme.Label("", 13, Theme.Warning);
    private readonly CheckBox _http = Theme.Check("Разрешить HTTP для этой подписки", false);
    private readonly Button _submit = Theme.Button("Добавить", true);
    private readonly Button _paste = Theme.Button("Вставить из буфера");
    private bool _busy, _closed;
    public ImportWindow(Func<string, string, bool, CancellationToken, Task> import, CancellationToken owner)
    {
        _import = import; _lifetime = CancellationTokenSource.CreateLinkedTokenSource(owner);
        Title = "Добавить в MOST"; Width = 620; Height = 620; MinHeight = 440; MinWidth = 440;
        Background = Theme.Background; Foreground = Theme.Text; FontFamily = new System.Windows.Media.FontFamily("Segoe UI");
        WindowStartupLocation = WindowStartupLocation.CenterOwner;
        Resources.MergedDictionaries.Add(new ResourceDictionary { Source = new Uri("/MOST;component/Views/Controls.xaml", UriKind.Relative) });
        var panel = new StackPanel { Margin = new Thickness(24) };
        Content = new ScrollViewer { Content = panel, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
        panel.Children.Add(Theme.Label("Добавить подключение", 25));
        panel.Children.Add(Theme.Label("Вставьте ссылку подписки или VLESS-ссылки от провайдера.", 13, Theme.Muted));
        panel.Children.Add(Theme.Label("Ссылка или конфигурация", 13));
        _input.MinHeight = 130; _input.MaxHeight = 240; AutomationProperties.SetName(_input, "Ссылка или конфигурация"); panel.Children.Add(_input);
        _paste.Click += (_, _) => {
            try { if (Clipboard.ContainsText()) _input.Text = Clipboard.GetText(); }
            catch (System.Runtime.InteropServices.COMException) { _error.Text = "Буфер обмена занят. Вставьте ссылку сочетанием Ctrl+V."; }
        }; panel.Children.Add(_paste); panel.Children.Add(_feedback);
        panel.Children.Add(Theme.Label("Название (необязательно)", 13)); AutomationProperties.SetName(_name, "Название подписки"); panel.Children.Add(_name);
        _http.Visibility = Visibility.Collapsed; panel.Children.Add(_http);
        _http.ToolTip = "HTTP передаёт ссылку и содержимое подписки без шифрования TLS.";
        panel.Children.Add(_error); AutomationProperties.SetLiveSetting(_error, AutomationLiveSetting.Assertive);
        _submit.Click += async (_, _) => await SubmitAsync(); panel.Children.Add(_submit);
        var cancel = Theme.Button("Отмена"); cancel.Click += (_, _) => Close(); panel.Children.Add(cancel);
        _input.TextChanged += async (_, _) => await PreviewAsync();
        Loaded += (_, _) => { _input.Focus(); _submit.IsEnabled = false; };
        Closing += (_, _) => { _closed = true; _lifetime.Cancel(); _preview?.Cancel(); };
        Closed += (_, _) => { _preview?.Dispose(); _lifetime.Dispose(); };
    }
    private async Task PreviewAsync()
    {
        _preview?.Cancel(); _preview?.Dispose();
        _preview = CancellationTokenSource.CreateLinkedTokenSource(_lifetime.Token); var ct = _preview.Token;
        var text = _input.Text.Trim(); _error.Text = "";
        _submit.IsEnabled = !_busy && text.Length > 0;
        _http.Visibility = Uri.TryCreate(text, UriKind.Absolute, out var uri) && uri.Scheme == "http" ? Visibility.Visible : Visibility.Collapsed;
        if (text.Length == 0) { _feedback.Text = ""; return; }
        try {
            await Task.Delay(220, ct);
            if (Uri.TryCreate(text, UriKind.Absolute, out uri) && uri.Scheme is "http" or "https") {
                _feedback.Text = "Подписка · " + uri.Host; return;
            }
            var profiles = await Task.Run(() => ProfileParser.Parse(text, "preview"), ct); ct.ThrowIfCancellationRequested();
            if (!_closed) _feedback.Text = $"Найдено подключений: {profiles.Count}";
        } catch (OperationCanceledException) { }
        catch (Exception) { if (!_closed && !ct.IsCancellationRequested) _feedback.Text = "Проверьте, что ссылка или конфигурация скопирована полностью."; }
    }
    private async Task SubmitAsync()
    {
        if (_busy || _input.Text.Trim().Length == 0) return;
        _busy = true; _submit.IsEnabled = false; _input.IsReadOnly = true; _name.IsReadOnly = true; _http.IsEnabled = false; _paste.IsEnabled = false;
        _submit.Content = "Добавляем…"; _error.Text = "";
        try {
            await _import(_input.Text, _name.Text, _http.IsChecked == true, _lifetime.Token);
            if (!_closed) DialogResult = true;
        } catch (OperationCanceledException) { if (!_closed) _error.Text = "Операция отменена. Можно попробовать снова."; }
        catch (Exception e) {
            if (!_closed) {
                _error.Text = e is UserError ? e.Message : "Не удалось добавить подключение. Проверьте интернет и попробуйте снова.";
                if (_error.Text.Contains("HTTP", StringComparison.OrdinalIgnoreCase)) _http.Visibility = Visibility.Visible;
            }
        } finally {
            _busy = false;
            if (!_closed) { _input.IsReadOnly = false; _name.IsReadOnly = false; _http.IsEnabled = true; _paste.IsEnabled = true;
                _submit.Content = "Добавить"; _submit.IsEnabled = _input.Text.Trim().Length > 0; _input.Focus(); }
        }
    }
}
