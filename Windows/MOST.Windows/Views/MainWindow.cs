using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Data;
using System.Windows.Input;
using System.Windows.Media;
using Most.Windows.Core;
using Forms = System.Windows.Forms;

namespace Most.Windows.Views;

public sealed class MainWindow : Window
{
    private readonly AppModel _model = new();
    private readonly CancellationTokenSource _lifetime = new();
    private CancellationTokenSource? _connect;
    private readonly StackPanel _content = new();
    private readonly TextBlock _notice = Theme.Label("", 13, Theme.Accent);
    private readonly List<Button> _nav = new();
    private readonly Forms.NotifyIcon _tray;
    private int _page;
    private bool _busy, _rendering, _allowClose;
    private string _search = "";
    private TextBlock? _statusTitle, _statusDetail, _exit, _route, _selected;
    private Button? _connectButton, _stopButton;

    public MainWindow()
    {
        Title = "MOST — Windows"; Width = 1120; Height = 780; MinWidth = 940; MinHeight = 650;
        Background = Theme.Background; Foreground = Theme.Text; FontFamily = new FontFamily("Segoe UI");
        WindowStartupLocation = WindowStartupLocation.CenterScreen;
        var grid = new Grid(); grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(210) });
        grid.ColumnDefinitions.Add(new ColumnDefinition()); Content = grid;
        var side = new DockPanel { Background = Theme.Panel, Margin = new Thickness(0, 0, 20, 0) }; grid.Children.Add(side);
        var brand = new StackPanel { Margin = new Thickness(22, 27, 10, 30) };
        brand.Children.Add(Theme.Label("M O S T", 30, Theme.Accent)); brand.Children.Add(Theme.Label("Связь без границ", 12, Theme.Muted));
        DockPanel.SetDock(brand, Dock.Top); side.Children.Add(brand);
        var footer = new StackPanel { Margin = new Thickness(18, 10, 10, 22) };
        footer.Children.Add(Theme.Label($"Windows x64 · {AppModel.Version}", 12, Theme.Muted));
        var trayButton = Theme.Button("Свернуть в трей"); trayButton.Click += (_, _) => Hide(); footer.Children.Add(trayButton);
        DockPanel.SetDock(footer, Dock.Bottom); side.Children.Add(footer);
        var navigation = new StackPanel { Margin = new Thickness(16, 0, 10, 0) };
        var titles = new[] { "Главная", "Серверы", "Подписки", "Приложения", "Настройки", "Журнал" };
        for (var i = 0; i < titles.Length; i++) {
            var index = i; var button = Theme.Button(titles[i]); button.HorizontalContentAlignment = HorizontalAlignment.Left;
            button.Click += (_, _) => { _page = index; Render(); }; navigation.Children.Add(button); _nav.Add(button);
        }
        side.Children.Add(navigation);
        var main = new DockPanel { Margin = new Thickness(8, 23, 27, 22) }; Grid.SetColumn(main, 1); grid.Children.Add(main);
        var top = new DockPanel { Margin = new Thickness(0, 0, 0, 14) };
        var add = Theme.Button("＋ Добавить", true); add.Click += async (_, _) => await ShowImportAsync();
        DockPanel.SetDock(add, Dock.Right); top.Children.Add(add);
        top.Children.Add(Theme.Label("Личное пространство", 15, Theme.Muted));
        DockPanel.SetDock(top, Dock.Top); main.Children.Add(top);
        _notice.Margin = new Thickness(0, 0, 0, 12); DockPanel.SetDock(_notice, Dock.Top); main.Children.Add(_notice);
        main.Children.Add(new ScrollViewer { Content = _content, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled });
        _tray = new Forms.NotifyIcon { Text = "MOST", Icon = System.Drawing.Icon.ExtractAssociatedIcon(Environment.ProcessPath!) ?? System.Drawing.SystemIcons.Application, Visible = true };
        _tray.DoubleClick += (_, _) => Dispatcher.Invoke(Restore);
        var trayMenu = new Forms.ContextMenuStrip();
        trayMenu.Items.Add("Открыть MOST", null, (_, _) => Dispatcher.Invoke(Restore));
        trayMenu.Items.Add("Отключить VPN", null, (_, _) => Dispatcher.InvokeAsync(async () => await StopAsync()));
        trayMenu.Items.Add("Выход", null, (_, _) => Dispatcher.Invoke(Close)); _tray.ContextMenuStrip = trayMenu;
        _model.Changed += () => Dispatcher.InvokeAsync(() => { if (!_allowClose) Render(); });
        _model.Connection.Changed += _ => Dispatcher.InvokeAsync(() => { if (!_allowClose) UpdateStatus(); });
        Loaded += async (_, _) => await RunAsync(async () => { await _model.InitializeAsync(_lifetime.Token); Render(); });
        Closing += async (_, e) => {
            if (_allowClose) return;
            e.Cancel = true; _notice.Text = "Отключение и завершение MOST…";
            _lifetime.Cancel(); _connect?.Cancel();
            try { await _model.Connection.StopAsync(); }
            catch (Exception) { _notice.Text = "Не удалось подтвердить остановку ядра. Проверьте процесс xray.exe."; }
            _allowClose = true; _tray.Dispose(); Close();
        };
        Render();
    }
    private void Restore() { Show(); WindowState = WindowState.Normal; Activate(); }
    private async Task RunAsync(Func<Task> action)
    {
        if (_busy || _lifetime.IsCancellationRequested) return;
        SetBusy(true); _notice.Text = "Выполняется…";
        try { await action(); _notice.Text = "Готово"; }
        catch (OperationCanceledException) { _notice.Text = "Операция отменена"; }
        catch (Exception e) { _notice.Text = e is UserError ? e.Message : $"Операция не выполнена ({e.GetType().Name}). Проверьте сеть и журнал."; }
        finally { SetBusy(false); }
    }
    private void SetBusy(bool busy)
    {
        _busy = busy; foreach (var nav in _nav) nav.IsEnabled = !busy;
        if (!busy && _page is 1 or 2) Render(); else UpdateStatus();
    }
    private void Render()
    {
        _rendering = true;
        try {
            _content.Children.Clear(); _statusTitle = _statusDetail = _exit = _route = _selected = null; _connectButton = _stopButton = null;
            for (var i = 0; i < _nav.Count; i++) _nav[i].Background = i == _page ? Theme.BorderColor : Theme.Input;
            switch (_page) { case 0: Home(); break; case 1: Servers(); break; case 2: Subscriptions(); break; case 3: Apps(); break; case 4: Settings(); break; case 5: Logs(); break; }
        } finally { _rendering = false; }
        UpdateStatus();
    }
    private void Heading(string title, string subtitle)
    {
        _content.Children.Add(Theme.Label(title, 30)); _content.Children.Add(Theme.Label(subtitle, 14, Theme.Muted));
    }
    private void Home()
    {
        Heading("Ваше подключение", "Выберите направление. MOST проверит доступ через выбранный сервер.");
        var hero = new StackPanel(); _statusTitle = Theme.Label("", 25, Theme.Accent); _statusDetail = Theme.Label("", 14, Theme.Muted);
        hero.Children.Add(_statusTitle); hero.Children.Add(_statusDetail);
        _exit = Theme.Label("", 23); _exit.Margin = new Thickness(0, 20, 0, 14); hero.Children.Add(_exit);
        _route = Theme.Label("", 14, Theme.Muted); hero.Children.Add(_route);
        var actions = new WrapPanel { Margin = new Thickness(0, 18, 0, 0) };
        _connectButton = Theme.Button("Подключить", true); _connectButton.MinWidth = 190;
        _connectButton.Click += async (_, _) => await ConnectAsync(); actions.Children.Add(_connectButton);
        _stopButton = Theme.Button("Отключить"); _stopButton.Click += async (_, _) => await StopAsync(); actions.Children.Add(_stopButton);
        hero.Children.Add(actions); _content.Children.Add(Theme.Card(hero));
        var choice = new StackPanel(); choice.Children.Add(Theme.Label("ВЫБРАННОЕ ПОДКЛЮЧЕНИЕ", 12, Theme.Muted));
        _selected = Theme.Label("", 18); choice.Children.Add(_selected);
        var change = Theme.Button("Выбрать сервер"); change.Click += (_, _) => { _page = 1; Render(); }; choice.Children.Add(change);
        _content.Children.Add(Theme.Card(choice));
        var hint = _model.State.TunMode ? "Режим VPN: трафик Windows направляется через TUN. Исключения задаются во вкладке «Приложения»."
            : "Режим локального прокси: программы нужно настроить на указанный HTTP/SOCKS-адрес вручную.";
        _content.Children.Add(Theme.Label(hint, 13, Theme.Muted));
    }
    private async Task ConnectAsync()
    {
        await RunAsync(async () => {
            _connect?.Cancel(); _connect?.Dispose(); _connect = CancellationTokenSource.CreateLinkedTokenSource(_lifetime.Token);
            await _model.ConnectAsync(_connect.Token); _page = 0; Render();
        });
    }
    private async Task StopAsync()
    {
        _connect?.Cancel();
        try { await _model.Connection.StopAsync(); _notice.Text = "VPN отключён"; }
        catch (Exception e) { _notice.Text = "Не удалось подтвердить остановку: " + e.GetType().Name; }
        UpdateStatus();
    }
    private void UpdateStatus()
    {
        var status = _model.Connection.Status;
        if (_statusTitle != null) _statusTitle.Text = status.Title;
        if (_statusDetail != null) _statusDetail.Text = status.Detail;
        if (_exit != null) _exit.Text = status.Ip.Length > 0 ? $"{status.Ip}   ·   {status.Country}" : "IP выхода пока не определён";
        if (_route != null) _route.Text = status.Profile == null ? "" : $"Активный профиль: {status.Profile.Name}\n{status.Profile.RouteLabel} · {status.Profile.Transport.ToUpperInvariant()}";
        if (_selected != null) _selected.Text = _model.State.Selected?.Display ?? "Пока нет подключений. Добавьте подписку или VLESS-ссылку.";
        if (_connectButton != null) { _connectButton.IsEnabled = !_busy && _model.State.Selected != null;
            _connectButton.Content = _model.Connection.HasSession ? "Переподключить" : "Подключить"; }
        if (_stopButton != null) _stopButton.IsEnabled = _model.Connection.HasSession;
        _tray.Text = status.Active ? "MOST — подключено" : "MOST — " + (status.Busy ? "подключение" : "нет соединения");
    }
    private sealed record Choice(string Id, string Title) { public override string ToString() => Title; }
    private void Servers()
    {
        Heading("Куда подключимся?", "Мосты и прямые подключения выбираются отдельно. Страна в названии ещё не подтверждает фактический выход.");
        var controls = new StackPanel(); var row = new WrapPanel();
        var groups = Theme.Combo(); groups.Items.Add(new Choice("", "Все подписки")); groups.Items.Add(new Choice("local", "Локальные профили"));
        foreach (var sub in _model.State.Subscriptions) groups.Items.Add(new Choice(sub.Id, sub.Name));
        groups.SelectedItem = groups.Items.Cast<Choice>().FirstOrDefault(g => g.Id == _model.State.GroupFilter) ?? groups.Items[0];
        var countries = Theme.Combo();
        foreach (var code in new[] { "", "nl", "de", "pl", "fi", "msk" }) countries.Items.Add(new Choice(code, code.Length == 0 ? "Все страны" : RoutePolicy.CountryName(code)));
        countries.SelectedItem = countries.Items.Cast<Choice>().FirstOrDefault(c => c.Id == _model.State.CountryFilter) ?? countries.Items[0];
        AutomationProperties.SetName(groups, "Подписка"); AutomationProperties.SetName(countries, "Страна");
        row.Children.Add(groups); row.Children.Add(countries); controls.Children.Add(row);
        var direct = Theme.Check("Прямые подключения (без галочки — только мосты)", _model.State.DirectRoutes); controls.Children.Add(direct);
        var search = Theme.TextInput(_search); AutomationProperties.SetName(search, "Поиск сервера"); controls.Children.Add(search);
        var list = new ListBox { Background = Theme.Panel, Foreground = Theme.Text, BorderThickness = new Thickness(0),
            MinHeight = 230, MaxHeight = 370, Margin = new Thickness(0, 8, 0, 14), FontSize = 14 };
        var factory = new FrameworkElementFactory(typeof(TextBlock)); factory.SetBinding(TextBlock.TextProperty, new Binding("Display"));
        factory.SetValue(TextBlock.TextWrappingProperty, TextWrapping.Wrap); factory.SetValue(FrameworkElement.MarginProperty, new Thickness(12));
        list.ItemTemplate = new DataTemplate { VisualTree = factory };
        void Populate() { list.ItemsSource = RoutePolicy.Filter(_model.State, _search); list.SelectedItem = list.Items.Cast<Profile>().FirstOrDefault(p => p.Id == _model.State.SelectedId); }
        Populate();
        var count = Theme.Label($"Подключений в списке: {list.Items.Count}", 12, Theme.Muted);
        controls.Children.Add(count); controls.Children.Add(list);
        _content.Children.Add(Theme.Card(controls));
        if (list.Items.Count == 0) _content.Children.Add(Theme.Label("Нет подходящих подключений. Переключите «Прямые подключения», измените фильтр или обновите подписку.", 14, Theme.Muted));
        async Task Filter() {
            if (_rendering) return;
            await RunAsync(() => _model.FiltersAsync(((Choice)groups.SelectedItem).Id, ((Choice)countries.SelectedItem).Id, direct.IsChecked == true, _lifetime.Token));
        }
        groups.SelectionChanged += async (_, _) => await Filter(); countries.SelectionChanged += async (_, _) => await Filter();
        direct.Checked += async (_, _) => await Filter(); direct.Unchecked += async (_, _) => await Filter();
        search.TextChanged += (_, _) => { _search = search.Text; _rendering = true; try { Populate(); count.Text = $"Подключений в списке: {list.Items.Count}"; } finally { _rendering = false; } };
        list.SelectionChanged += async (_, _) => {
            if (_rendering || list.SelectedItem is not Profile selected) return;
            await RunAsync(() => _model.SelectAsync(selected.Id, _lifetime.Token));
        };
        var connect = Theme.Button("Подключить выбранный сервер", true); connect.IsEnabled = !_busy && _model.State.Selected != null;
        connect.Click += async (_, _) => await ConnectAsync(); _content.Children.Add(connect);
    }
    private void Subscriptions()
    {
        Heading("Ваши подписки", "Обновление заменяет только профили выбранной подписки. Ссылки доступа здесь не отображаются.");
        foreach (var sub in _model.State.Subscriptions) {
            var card = new StackPanel(); card.Children.Add(Theme.Label(sub.Name, 20));
            card.Children.Add(Theme.Label(new Uri(sub.Url).Host, 13, Theme.Muted));
            card.Children.Add(Theme.Label($"Подключений: {_model.State.Profiles.Count(p => p.GroupId == sub.Id)} · Обновлено: {sub.Updated?.ToString("dd.MM.yyyy HH:mm") ?? "—"}", 13, Theme.Muted));
            card.Children.Add(Theme.Label(AppModel.UsageText(sub.Usage), 13, Theme.Muted));
            var actions = new WrapPanel(); var refresh = Theme.Button("Обновить"); refresh.IsEnabled = !_busy;
            refresh.Click += async (_, _) => await RunAsync(() => _model.RefreshAsync(sub.Id, _lifetime.Token)); actions.Children.Add(refresh);
            var rename = Theme.Button("Переименовать"); rename.Click += async (_, _) => {
                var name = AskText("Название подписки", sub.Name); if (name != null) await RunAsync(() => _model.RenameAsync(sub.Id, name, _lifetime.Token));
            }; actions.Children.Add(rename);
            var remove = Theme.Button("Удалить"); remove.Click += async (_, _) => {
                if (MessageBox.Show(this, "Удалить эту подписку и её профили из MOST?", "Удаление подписки", MessageBoxButton.YesNo, MessageBoxImage.Question) == MessageBoxResult.Yes)
                    await RunAsync(() => _model.RemoveAsync(sub.Id, _lifetime.Token));
            }; actions.Children.Add(remove); card.Children.Add(actions); _content.Children.Add(Theme.Card(card));
        }
        if (_model.State.Subscriptions.Count == 0) _content.Children.Add(Theme.Card(Theme.Label("Здесь появятся ваши подписки. Нажмите «Добавить» и вставьте HTTP/HTTPS-ссылку.", 16)));
        _content.Children.Add(Theme.Label($"Локальные профили: {_model.State.Profiles.Count(p => p.GroupId == "local")}", 14, Theme.Muted));
    }
    private void Apps()
    {
        Heading("Приложения без VPN", "Выберите .exe программы, которую нужно направлять напрямую. Изменения применяются при следующем подключении в режиме TUN.");
        var values = _model.State.ExcludedApps.ToList(); var list = new ListBox { ItemsSource = values, MinHeight = 180,
            MaxHeight = 300, Background = Theme.Input, Foreground = Theme.Text, BorderBrush = Theme.BorderColor, FontSize = 13 };
        AutomationProperties.SetName(list, "Исключённые программы");
        var card = new StackPanel(); card.Children.Add(list); var actions = new WrapPanel { Margin = new Thickness(0, 16, 0, 0) };
        var add = Theme.Button("Выбрать программу .exe"); add.Click += (_, _) => {
            var file = new Microsoft.Win32.OpenFileDialog { Filter = "Программы Windows (*.exe)|*.exe", CheckFileExists = true, Multiselect = true };
            if (file.ShowDialog(this) == true) { values.AddRange(file.FileNames.Where(p => !values.Contains(p, StringComparer.OrdinalIgnoreCase))); list.ItemsSource = null; list.ItemsSource = values; }
        }; actions.Children.Add(add);
        var remove = Theme.Button("Убрать исключение"); remove.Click += (_, _) => { if (list.SelectedItem is string path) { values.Remove(path); list.ItemsSource = null; list.ItemsSource = values; } }; actions.Children.Add(remove);
        var save = Theme.Button("Сохранить", true); save.Click += async (_, _) => await RunAsync(() => _model.SaveSettingsAsync(_model.State.TunMode, values, _lifetime.Token)); actions.Children.Add(save);
        card.Children.Add(actions); _content.Children.Add(Theme.Card(card));
        _content.Children.Add(Theme.Label("Для программ, состоящих из нескольких процессов, добавьте нужные .exe отдельно. Например, лаунчер и сама игра могут быть разными файлами.", 13, Theme.Muted));
    }
    private void Settings()
    {
        Heading("Как работает MOST", "Настройки подключения для этого компьютера.");
        var card = new StackPanel(); var tun = Theme.Check("Полный VPN для Windows (TUN)", _model.State.TunMode); card.Children.Add(tun);
        card.Children.Add(Theme.Label("Включено: приложения Windows используют VPN, кроме выбранных исключений. Выключено: только локальный HTTP/SOCKS-прокси для ручной настройки программ.", 14, Theme.Muted));
        var save = Theme.Button("Сохранить режим", true); save.Click += async (_, _) => await RunAsync(() => _model.SaveSettingsAsync(tun.IsChecked == true, _model.State.ExcludedApps, _lifetime.Token)); card.Children.Add(save);
        card.Children.Add(Theme.Label("После изменения режима переподключитесь.", 13, Theme.Muted));
        card.Children.Add(Theme.Label(_model.Connection.ProxyAddress, 13, Theme.Muted)); _content.Children.Add(Theme.Card(card));
        var info = new StackPanel(); info.Children.Add(Theme.Label("MOST для Windows · " + AppModel.Version, 19));
        info.Children.Add(Theme.Label("Xray 26.10.21 · Wintun 0.14.1\nПодписки и настройки защищаются Windows DPAPI для текущего пользователя. HWID Windows сохраняется при обновлении приложения.", 13, Theme.Muted));
        var releases = Theme.Button("Версии MOST для Windows"); releases.Click += (_, _) => Process.Start(new ProcessStartInfo("https://github.com/Tombstone1922/MOST/releases/tag/windows-v0.1.0") { UseShellExecute = true }); info.Children.Add(releases);
        _content.Children.Add(Theme.Card(info));
    }
    private void Logs()
    {
        Heading("Журнал подключения", "Последние сообщения Xray. Ссылки и UUID скрываются перед отображением.");
        var box = Theme.TextInput(_model.Connection.Logs, true); box.IsReadOnly = true; box.Height = 340; box.FontFamily = new FontFamily("Consolas");
        _content.Children.Add(box); var actions = new WrapPanel();
        var refresh = Theme.Button("Обновить журнал"); refresh.Click += (_, _) => box.Text = _model.Connection.Logs; actions.Children.Add(refresh);
        var copy = Theme.Button("Скопировать"); copy.Click += (_, _) => { Clipboard.SetText(_model.Connection.Logs.Length == 0 ? "Журнал пуст" : _model.Connection.Logs); _notice.Text = "Журнал скопирован"; }; actions.Children.Add(copy);
        _content.Children.Add(actions);
    }
    private async Task ShowImportAsync()
    {
        if (_busy) return;
        var dialog = new Window { Owner = this, Title = "Добавить в MOST", Width = 660, Height = 550,
            MinHeight = 440, Background = Theme.Background, Foreground = Theme.Text, WindowStartupLocation = WindowStartupLocation.CenterOwner };
        var panel = new StackPanel { Margin = new Thickness(25) }; dialog.Content = new ScrollViewer { Content = panel, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
        panel.Children.Add(Theme.Label("Подписка или подключение", 24));
        panel.Children.Add(Theme.Label("HTTP/HTTPS-ссылка, несколько VLESS-ссылок или Xray JSON. Содержимое подписки загружается после нажатия «Добавить».", 13, Theme.Muted));
        var text = Theme.TextInput("", true); text.MinHeight = 140; AutomationProperties.SetName(text, "Ссылка или конфигурация"); panel.Children.Add(text);
        var paste = Theme.Button("Вставить из буфера"); paste.Click += (_, _) => text.Text = Clipboard.ContainsText() ? Clipboard.GetText() : ""; panel.Children.Add(paste);
        var name = Theme.TextInput(); AutomationProperties.SetName(name, "Название подписки, необязательно"); panel.Children.Add(Theme.Label("Название подписки (необязательно)", 13, Theme.Muted)); panel.Children.Add(name);
        var http = Theme.Check("Разрешаю загрузку этой подписки по обычному HTTP", false); panel.Children.Add(http);
        panel.Children.Add(Theme.Label("HTTP передаёт ссылку подписки и ответ без шифрования TLS.", 12, Theme.Muted));
        var add = Theme.Button("Добавить", true); add.Click += (_, _) => dialog.DialogResult = true; panel.Children.Add(add);
        if (dialog.ShowDialog() == true) await RunAsync(async () => { await _model.ImportAsync(text.Text, name.Text, http.IsChecked == true, _lifetime.Token); _page = 1; Render(); });
    }
    private string? AskText(string title, string value)
    {
        var dialog = new Window { Owner = this, Title = title, Width = 440, Height = 230, Background = Theme.Background, WindowStartupLocation = WindowStartupLocation.CenterOwner, ResizeMode = ResizeMode.NoResize };
        var panel = new StackPanel { Margin = new Thickness(22) }; panel.Children.Add(Theme.Label(title, 20));
        var text = Theme.TextInput(value); panel.Children.Add(text); var save = Theme.Button("Сохранить", true); save.Click += (_, _) => dialog.DialogResult = true; panel.Children.Add(save); dialog.Content = panel;
        return dialog.ShowDialog() == true ? text.Text : null;
    }
}
