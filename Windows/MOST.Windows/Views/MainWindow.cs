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
using System.Windows.Media;
using System.Windows.Threading;
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
    private readonly TextBlock _sideStatus = Theme.Label("Не подключено", 12, Theme.Muted);
    private readonly List<Button> _nav = new();
    private readonly Forms.NotifyIcon _tray;
    private readonly DispatcherTimer _clock = new() { Interval = TimeSpan.FromSeconds(1) };
    private Task? _updateLoop;
    private int _page;
    private bool _busy, _rendering, _allowClose, _closing, _favoritesOnly;
    private bool? _siteRu, _siteRf, _siteSu;
    private string? _siteDirectDraft, _siteProxyDraft;
    private string _search = "", _appSearch = "";
    private IReadOnlyList<DesktopApp> _catalog = Array.Empty<DesktopApp>();
    private List<string>? _appDraft;
    private TextBlock? _statusTitle, _statusDetail, _exit, _country, _route, _duration, _latency, _updateDetail;
    private Button? _power, _connectButton, _stopButton, _checkUpdate, _downloadUpdate, _installUpdate;
    private ProgressBar? _progress;
    private Border? _updateBanner;
    private string? _shownUpdate;

    public MainWindow()
    {
        Resources.MergedDictionaries.Add(new ResourceDictionary { Source = new Uri("/MOST;component/Views/Controls.xaml", UriKind.Relative) });
        Title = "MOST · Связь без границ"; Width = 1220; Height = 820; MinWidth = 1010; MinHeight = 700;
        Background = Theme.Background; Foreground = Theme.Text; FontFamily = new FontFamily("Segoe UI"); FontSize = 14;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;
        var grid = new Grid(); grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(212) });
        grid.ColumnDefinitions.Add(new ColumnDefinition()); Content = grid;
        var side = new DockPanel { Background = Theme.Panel }; grid.Children.Add(side);
        var brand = new StackPanel { Margin = new Thickness(26, 30, 10, 32) };
        var wordmark = Theme.Label("M O S T", 29, Theme.Accent); wordmark.FontWeight = FontWeights.SemiBold;
        brand.Children.Add(wordmark); brand.Children.Add(Theme.Label("Связь без границ", 12, Theme.Muted));
        DockPanel.SetDock(brand, Dock.Top); side.Children.Add(brand);
        var footer = new StackPanel { Margin = new Thickness(18, 10, 18, 20) };
        footer.Children.Add(_sideStatus); footer.Children.Add(Theme.Label($"Windows · {AppModel.Version}", 11, Theme.Muted));
        var trayButton = Theme.Button("Свернуть в трей"); trayButton.FontSize = 12; trayButton.Margin = new Thickness(0, 8, 0, 0);
        trayButton.Click += (_, _) => Hide(); footer.Children.Add(trayButton);
        DockPanel.SetDock(footer, Dock.Bottom); side.Children.Add(footer);
        var navigation = new StackPanel { Margin = new Thickness(12, 0, 12, 0) };
        var titles = new[] { "Главная", "Серверы", "Подписки", "Приложения", "Настройки", "Журнал", "Обновления", "Сайты" };
        var icons = new[] { "\uE80F", "\uE774", "\uE8F1", "\uE71D", "\uE713", "\uE9D9", "\uE895", "\uE774" };
        for (var i = 0; i < titles.Length; i++) {
            var index = i; var button = Theme.Nav(titles[i], icons[i]);
            button.Click += (_, _) => Navigate(index); navigation.Children.Add(button); _nav.Add(button);
        }
        side.Children.Add(navigation);
        var main = new DockPanel { Margin = new Thickness(32, 23, 30, 24), MaxWidth = 1160, HorizontalAlignment = HorizontalAlignment.Stretch };
        Grid.SetColumn(main, 1); grid.Children.Add(main);
        var top = new DockPanel { Margin = new Thickness(0, 0, 0, 12) };
        var add = Theme.Button("＋ Добавить подписку", true); add.Click += async (_, _) => await ShowImportAsync();
        DockPanel.SetDock(add, Dock.Right); top.Children.Add(add);
        var label = Theme.Label("ВАШЕ ЛИЧНОЕ ПРОСТРАНСТВО", 11, Theme.Muted); label.VerticalAlignment = VerticalAlignment.Center; top.Children.Add(label);
        DockPanel.SetDock(top, Dock.Top); main.Children.Add(top);
        _notice.Margin = new Thickness(0, 0, 0, 12); _notice.Visibility = Visibility.Collapsed;
        DockPanel.SetDock(_notice, Dock.Top); main.Children.Add(_notice);
        main.Children.Add(new ScrollViewer { Content = _content, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled });
        _tray = new Forms.NotifyIcon { Text = "MOST", Icon = System.Drawing.Icon.ExtractAssociatedIcon(Environment.ProcessPath!) ?? System.Drawing.SystemIcons.Application, Visible = true };
        _tray.DoubleClick += (_, _) => Dispatcher.Invoke(Restore);
        _tray.BalloonTipClicked += (_, _) => Dispatcher.Invoke(() => { Restore(); Navigate(6); });
        var trayMenu = new Forms.ContextMenuStrip();
        trayMenu.Items.Add("Открыть MOST", null, (_, _) => Dispatcher.Invoke(Restore));
        trayMenu.Items.Add("Отключить VPN", null, (_, _) => Dispatcher.InvokeAsync(async () => await StopAsync()));
        trayMenu.Items.Add("Проверить обновления", null, (_, _) => Dispatcher.InvokeAsync(async () => { Restore(); Navigate(6); await _model.Updates.CheckAsync(_lifetime.Token); }));
        trayMenu.Items.Add("Выход", null, (_, _) => Dispatcher.Invoke(Close)); _tray.ContextMenuStrip = trayMenu;
        _model.Changed += () => Dispatcher.InvokeAsync(() => { if (!_closing) Render(); });
        _model.Connection.Changed += _ => Dispatcher.InvokeAsync(() => { if (!_closing) UpdateStatus(); });
        _model.Updates.Changed += () => Dispatcher.InvokeAsync(() => {
            if (_closing) return;
            if (_shownUpdate != _model.Updates.Available?.Version && _page is 0 or 6) Render();
            else UpdateStatus();
        });
        _clock.Tick += (_, _) => UpdateStatus(); _clock.Start();
        Loaded += async (_, _) => {
            await RunAsync(async () => { await _model.InitializeAsync(_lifetime.Token); Render(); });
            if (_model.Initialized) _updateLoop = ObserveUpdatesAsync(_lifetime.Token);
        };
        Closing += async (_, e) => {
            if (_allowClose) return; e.Cancel = true;
            if (_closing) return;
            _closing = true; Notice("Завершаем соединение…"); _clock.Stop();
            _lifetime.Cancel(); _connect?.Cancel();
            try { await _model.StopAsync(); if (_updateLoop != null) await _updateLoop; }
            catch (OperationCanceledException) { }
            catch (Exception) { }
            _allowClose = true; _tray.Dispose(); Close();
        };
        Render();
    }
    private void Navigate(int page) { _page = page; Render(); }
    private void Restore() { Show(); WindowState = WindowState.Normal; Activate(); }
    private void Notice(string text) { _notice.Text = text; _notice.Visibility = text.Length == 0 ? Visibility.Collapsed : Visibility.Visible; }
    private async Task RunAsync(Func<Task> action)
    {
        if (_busy || _lifetime.IsCancellationRequested) return;
        SetBusy(true); Notice("");
        try { await action(); }
        catch (OperationCanceledException) { if (!_closing) Notice("Операция отменена"); }
        catch (Exception e) { if (!_closing) Notice(e is UserError ? e.Message : $"Не удалось выполнить действие ({e.GetType().Name}). Попробуйте ещё раз."); }
        finally { SetBusy(false); }
    }
    private void SetBusy(bool busy)
    {
        _busy = busy; foreach (var nav in _nav) nav.IsEnabled = !busy;
        if (!busy && !_closing) Render(); else UpdateStatus();
    }
    private void Render()
    {
        _rendering = true;
        try {
            _content.Children.Clear(); _statusTitle = _statusDetail = _exit = _country = _route = _duration = _latency = _updateDetail = null;
            _power = _connectButton = _stopButton = _checkUpdate = _downloadUpdate = _installUpdate = null; _progress = null; _updateBanner = null;
            _shownUpdate = _model.Updates.Available?.Version;
            for (var i = 0; i < _nav.Count; i++) { _nav[i].Background = i == _page ? Theme.AccentSoft : Brushes.Transparent; _nav[i].Foreground = i == _page ? Theme.Accent : Theme.Muted; }
            switch (_page) { case 0: Home(); break; case 1: Servers(); break; case 2: Subscriptions(); break; case 3: Apps(); break; case 4: Settings(); break; case 5: Logs(); break; case 6: Updates(); break; case 7: Sites(); break; }
        } finally { _rendering = false; }
        UpdateStatus();
    }
    private void Heading(string title, string subtitle)
    {
        var heading = Theme.Label(title, 29); heading.FontWeight = FontWeights.SemiBold;
        _content.Children.Add(heading); var sub = Theme.Label(subtitle, 13, Theme.Muted); sub.Margin = new Thickness(0, 0, 0, 22); _content.Children.Add(sub);
    }
    private void Home()
    {
        Heading("Связь без границ", "Ваш интернет. Ваша страна. Одно нажатие.");
        var hero = new StackPanel(); hero.Children.Add(Theme.Pill("ЛИЧНОЕ ПОДКЛЮЧЕНИЕ", true));
        _power = Theme.Button("", true); _power.Width = _power.Height = 118; _power.MinHeight = 0;
        _power.Template = Theme.RoundedTemplate(59); _power.HorizontalAlignment = HorizontalAlignment.Center;
        _power.Margin = new Thickness(0, 12, 0, 18); _power.FontFamily = new FontFamily("Segoe MDL2 Assets"); _power.FontSize = 38; _power.Content = "\uE7E8";
        _power.Click += async (_, _) => { if (_model.Connection.HasSession) await StopAsync(); else await ConnectAsync(); }; hero.Children.Add(_power);
        _statusTitle = Theme.Label("", 24); _statusTitle.TextAlignment = TextAlignment.Center; hero.Children.Add(_statusTitle);
        _statusDetail = Theme.Label("", 12, Theme.Muted); _statusDetail.TextAlignment = TextAlignment.Center; _statusDetail.MinHeight = 36; hero.Children.Add(_statusDetail);
        var stats = new Grid { Margin = new Thickness(0, 16, 0, 4) };
        for (var i = 0; i < 3; i++) stats.ColumnDefinitions.Add(new ColumnDefinition());
        _country = Stat(stats, 0, "СТРАНА ВЫХОДА"); _duration = Stat(stats, 1, "ВРЕМЯ СЕАНСА"); _latency = Stat(stats, 2, "ОТКЛИК"); hero.Children.Add(stats);
        _exit = Theme.Label("", 12, Theme.Muted); _exit.TextAlignment = TextAlignment.Center; hero.Children.Add(_exit);
        var actions = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 14, 0, 0) };
        _connectButton = Theme.Button("Подключить", true); _connectButton.Click += async (_, _) => await ConnectAsync(); actions.Children.Add(_connectButton);
        _stopButton = Theme.Button("Отключить"); _stopButton.Click += async (_, _) => await StopAsync(); actions.Children.Add(_stopButton); hero.Children.Add(actions);
        var choice = QuickCountries();
        _content.Children.Add(Theme.Columns(Theme.Card(hero), Theme.Card(choice), 1.08, 1));
        var routeCard = new StackPanel(); routeCard.Children.Add(Theme.Label("ВАШ МАРШРУТ", 11, Theme.Muted));
        _route = Theme.Label("", 15); routeCard.Children.Add(_route);
        var links = new WrapPanel();
        var servers = Theme.Button("Все серверы →"); servers.Click += (_, _) => Navigate(1); links.Children.Add(servers);
        var apps = Theme.Button($"Без VPN: {_model.State.ExcludedApps.Count} приложений"); apps.Click += (_, _) => Navigate(3); links.Children.Add(apps);
        routeCard.Children.Add(links); _content.Children.Add(Theme.Card(routeCard));
        var update = new DockPanel(); var open = Theme.Button("Посмотреть", true); open.Margin = new Thickness(10, 0, 0, 0);
        open.Click += (_, _) => Navigate(6); DockPanel.SetDock(open, Dock.Right); update.Children.Add(open);
        update.Children.Add(Theme.Label("Доступно обновление MOST. Посмотрите, что нового.", 13));
        _updateBanner = Theme.Card(update); _updateBanner.Background = Theme.AccentSoft; _content.Children.Add(_updateBanner);
    }
    private static TextBlock Stat(Grid grid, int column, string title)
    {
        var panel = new StackPanel(); var caption = Theme.Label(title, 9, Theme.Muted); caption.TextAlignment = TextAlignment.Center; panel.Children.Add(caption);
        var value = Theme.Label("—", 15); value.FontWeight = FontWeights.SemiBold; value.TextAlignment = TextAlignment.Center; panel.Children.Add(value);
        Grid.SetColumn(panel, column); grid.Children.Add(panel); return value;
    }
    private sealed record Choice(string Id, string Title) { public override string ToString() => Title; }
    private ComboBox GroupPicker(string selected, bool all)
    {
        var groups = Theme.Combo(); groups.HorizontalAlignment = HorizontalAlignment.Stretch; groups.Margin = new Thickness(0, 0, 0, 12);
        if (all) groups.Items.Add(new Choice("", "Все подписки"));
        foreach (var sub in _model.State.Subscriptions) groups.Items.Add(new Choice(sub.Id, sub.Name));
        if (_model.State.Profiles.Any(p => p.GroupId == "local")) groups.Items.Add(new Choice("local", "Мои подключения"));
        groups.SelectedItem = groups.Items.Cast<Choice>().FirstOrDefault(x => x.Id == selected) ?? groups.Items.Cast<Choice>().FirstOrDefault();
        AutomationProperties.SetName(groups, "Подписка"); return groups;
    }
    private StackPanel QuickCountries()
    {
        var box = new StackPanel(); box.Children.Add(Theme.Label("Куда подключимся?", 21));
        box.Children.Add(Theme.Label("Выберите страну — MOST найдёт рабочее соединение.", 12, Theme.Muted));
        var groupId = _model.State.AutoCountry ? _model.State.AutoGroupId : _model.State.GroupFilter;
        var picker = GroupPicker(groupId, false); box.Children.Add(picker);
        var direct = Theme.Check("Прямые подключения", _model.State.DirectRoutes); box.Children.Add(direct);
        var countries = new WrapPanel(); var hint = Theme.Label("", 12, Theme.Muted);
        void Populate() {
            countries.Children.Clear();
            var group = (picker.SelectedItem as Choice)?.Id ?? "";
            var codes = _model.State.Profiles.Where(p => p.GroupId == group && p.IsBridge != (direct.IsChecked == true) && p.Country.Length > 0)
                .Select(p => p.Country).Distinct().OrderBy(c => c).ToArray();
            foreach (var code in codes) {
                var total = RoutePolicy.Candidates(_model.State, group, code, direct.IsChecked == true).Count;
                var active = _model.State.AutoCountry && _model.State.AutoCountryCode == code && _model.State.AutoGroupId == group && _model.State.AutoDirect == (direct.IsChecked == true);
                var button = Theme.Button($"{code.ToUpperInvariant()}   {RoutePolicy.CountryName(code)}"); button.Width = 173; button.MinHeight = 53;
                if (active) { button.Background = Theme.AccentSoft; button.Foreground = Theme.Accent; button.BorderBrush = Theme.Accent; }
                button.ToolTip = $"Автоподбор · подключений: {total}"; button.IsEnabled = !_busy;
                button.Click += async (_, _) => await RunAsync(() => _model.ChooseCountryAsync(group, code, direct.IsChecked == true, _lifetime.Token));
                countries.Children.Add(button);
            }
            hint.Text = codes.Length == 0 ? "Нет стран для этого типа подключения. Переключите тип или добавьте подписку." : "Рабочее соединение запоминается. При сбое MOST попробует другое в той же стране.";
        }
        picker.SelectionChanged += (_, _) => Populate(); direct.Checked += (_, _) => Populate(); direct.Unchecked += (_, _) => Populate();
        Populate(); box.Children.Add(countries); hint.Margin = new Thickness(0, 10, 0, 0); box.Children.Add(hint); return box;
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
        try { await _model.StopAsync(); if (!_closing) Notice(""); }
        catch (Exception e) { Notice("Не удалось завершить соединение: " + e.GetType().Name); }
        UpdateStatus();
    }
    private void UpdateStatus()
    {
        var status = _model.Connection.Status; var connection = _model.Connection;
        if (_statusTitle != null) { _statusTitle.Text = status.Title; _statusTitle.Foreground = status.Active ? Theme.Accent : Theme.Text; }
        if (_statusDetail != null) _statusDetail.Text = status.Detail;
        if (_exit != null) _exit.Text = status.Ip.Length > 0 ? "IP · " + status.Ip : "IP появится после подключения";
        if (_country != null) _country.Text = status.Country.Length > 0 ? (status.Country == "RU" ? "RU" : status.Country) : "—";
        if (_latency != null) _latency.Text = connection.LatencyMs is int ms ? $"{ms} мс" : "—";
        if (_duration != null) _duration.Text = connection.ConnectedSince is DateTimeOffset since ? (DateTimeOffset.Now - since).ToString(@"hh\:mm\:ss") : "—";
        if (_route != null) {
            var selected = _model.State.Selected;
            var mode = _model.State.AutoCountry ? $"Автоподбор · {RoutePolicy.CountryName(_model.State.AutoCountryCode)} · {(_model.State.AutoDirect ? "Прямое" : "Мост")}" : selected == null ? "Добавьте подписку, чтобы начать" : $"{RoutePolicy.CountryName(selected.Country)} · {selected.RouteLabel} · вручную";
            _route.Text = mode + (status.Profile != null ? "\nСейчас: " + status.Profile.Name + " · " + status.Profile.Transport.ToUpperInvariant() : selected != null ? "\n" + selected.Name : "");
        }
        if (_connectButton != null) { _connectButton.IsEnabled = !_busy && _model.State.Selected != null; _connectButton.Content = connection.HasSession ? "Переподключить" : "Подключить"; }
        if (_stopButton != null) { _stopButton.IsEnabled = connection.HasSession; _stopButton.Visibility = connection.HasSession ? Visibility.Visible : Visibility.Collapsed; }
        if (_power != null) { _power.IsEnabled = connection.HasSession || (!_busy && _model.State.Selected != null); _power.Background = status.Active ? Theme.Accent : Theme.Input; _power.Foreground = status.Active ? Theme.Background : Theme.Accent;
            AutomationProperties.SetName(_power, connection.HasSession ? "Отключить VPN" : "Подключить VPN"); }
        _sideStatus.Text = status.Active ? "●  VPN подключён" : status.Busy ? "●  Подключение…" : "○  Не подключено";
        _sideStatus.Foreground = status.Active ? Theme.Accent : Theme.Muted;
        _tray.Text = status.Active ? "MOST — подключено" : "MOST — " + (status.Busy ? "подключение" : "нет соединения");
        var updates = _model.Updates;
        if (_updateBanner != null) _updateBanner.Visibility = updates.Available == null ? Visibility.Collapsed : Visibility.Visible;
        if (_updateDetail != null) _updateDetail.Text = updates.Message + (updates.Busy && updates.Progress > 0 ? $"  {updates.Progress}%" : "");
        if (_checkUpdate != null) _checkUpdate.IsEnabled = !updates.Busy && !_busy;
        if (_downloadUpdate != null) { _downloadUpdate.IsEnabled = updates.Available != null && !updates.Busy && !_busy; _downloadUpdate.Visibility = updates.Available != null && updates.DownloadedPath == null ? Visibility.Visible : Visibility.Collapsed; }
        if (_installUpdate != null) { _installUpdate.IsEnabled = !updates.Busy && !_busy; _installUpdate.Visibility = updates.DownloadedPath == null ? Visibility.Collapsed : Visibility.Visible; }
        if (_progress != null) { _progress.Value = updates.Progress; _progress.Visibility = updates.Busy ? Visibility.Visible : Visibility.Collapsed; _progress.IsIndeterminate = updates.Busy && updates.Progress == 0; }
        _nav[6].Foreground = updates.Available != null ? Theme.Accent : (_page == 6 ? Theme.Accent : Theme.Muted);
    }
    private void Servers()
    {
        Heading("Выберите своё направление", "Автоподбор по стране — на главной. Здесь можно выбрать конкретный сервер.");
        var controls = new StackPanel(); var row = new WrapPanel(); var groups = GroupPicker(_model.State.GroupFilter, true); row.Children.Add(groups);
        var countries = Theme.Combo(); countries.Margin = new Thickness(10, 0, 0, 12);
        foreach (var code in new[] { "", "nl", "de", "pl", "fi", "msk" }) countries.Items.Add(new Choice(code, code.Length == 0 ? "Все страны" : RoutePolicy.CountryName(code)));
        countries.SelectedItem = countries.Items.Cast<Choice>().FirstOrDefault(c => c.Id == _model.State.CountryFilter) ?? countries.Items[0];
        AutomationProperties.SetName(countries, "Страна"); row.Children.Add(countries); controls.Children.Add(row);
        var options = new WrapPanel(); var direct = Theme.Check("Прямые подключения", _model.State.DirectRoutes); direct.Margin = new Thickness(0, 6, 24, 13);
        var favorites = Theme.Check("Только избранное", _favoritesOnly); options.Children.Add(direct); options.Children.Add(favorites); controls.Children.Add(options);
        var search = Theme.TextInput(_search); AutomationProperties.SetName(search, "Поиск сервера"); search.ToolTip = "Найти сервер по названию"; controls.Children.Add(search);
        var list = new ListBox { Background = Theme.Panel, Foreground = Theme.Text, BorderThickness = new Thickness(0),
            MinHeight = 200, MaxHeight = 330, Margin = new Thickness(0, 0, 0, 14), FontSize = 14, HorizontalContentAlignment = HorizontalAlignment.Stretch };
        var factory = new FrameworkElementFactory(typeof(TextBlock)); factory.SetBinding(TextBlock.TextProperty, new Binding("Display"));
        factory.SetValue(TextBlock.TextWrappingProperty, TextWrapping.Wrap); factory.SetValue(TextBlock.LineHeightProperty, 24.0); factory.SetValue(FrameworkElement.MarginProperty, new Thickness(12));
        list.ItemTemplate = new DataTemplate { VisualTree = factory };
        var count = Theme.Label("", 12, Theme.Muted);
        void Populate() {
            list.ItemsSource = RoutePolicy.Filter(_model.State, _search).Where(p => !_favoritesOnly || _model.State.Favorites.Contains(p.Id)).ToArray();
            list.SelectedItem = list.Items.Cast<Profile>().FirstOrDefault(p => p.Id == _model.State.SelectedId);
            count.Text = list.Items.Count == 0 ? "Ничего не найдено. Попробуйте другой фильтр или обновите подписку." : $"Доступно подключений: {list.Items.Count}";
        }
        Populate(); controls.Children.Add(count); controls.Children.Add(list);
        async Task Filter() { if (_rendering) return; await RunAsync(() => _model.FiltersAsync((groups.SelectedItem as Choice)?.Id ?? "", ((Choice)countries.SelectedItem).Id, direct.IsChecked == true, _lifetime.Token)); }
        groups.SelectionChanged += async (_, _) => await Filter(); countries.SelectionChanged += async (_, _) => await Filter();
        direct.Checked += async (_, _) => await Filter(); direct.Unchecked += async (_, _) => await Filter();
        favorites.Checked += (_, _) => { _favoritesOnly = true; _rendering = true; try { Populate(); } finally { _rendering = false; } };
        favorites.Unchecked += (_, _) => { _favoritesOnly = false; _rendering = true; try { Populate(); } finally { _rendering = false; } };
        search.TextChanged += (_, _) => { _search = search.Text; _rendering = true; try { Populate(); } finally { _rendering = false; } };
        list.SelectionChanged += async (_, _) => { if (!_rendering && list.SelectedItem is Profile selected) await RunAsync(() => _model.SelectAsync(selected.Id, _lifetime.Token)); };
        var actions = new WrapPanel(); var connect = Theme.Button("Подключить", true); connect.IsEnabled = !_busy && list.SelectedItem != null;
        connect.Click += async (_, _) => await ConnectAsync(); actions.Children.Add(connect);
        var star = Theme.Button(_model.State.Favorites.Contains(_model.State.SelectedId) ? "★ Убрать из избранного" : "☆ В избранное"); star.IsEnabled = !_busy && list.SelectedItem != null;
        star.Click += async (_, _) => { if (list.SelectedItem is Profile p) await RunAsync(() => _model.FavoriteAsync(p.Id, _lifetime.Token)); }; actions.Children.Add(star);
        controls.Children.Add(actions); _content.Children.Add(Theme.Card(controls));
    }
    private void Subscriptions()
    {
        Heading("Ваши подписки", "Все направления в одном месте. Назовите подписки так, как удобно вам.");
        foreach (var sub in _model.State.Subscriptions) {
            var card = new StackPanel(); card.Children.Add(Theme.Label(sub.Name, 21));
            card.Children.Add(Theme.Label(new Uri(sub.Url).Host, 12, Theme.Muted));
            var profiles = _model.State.Profiles.Where(p => p.GroupId == sub.Id).ToArray();
            var badges = new WrapPanel(); badges.Children.Add(Theme.Pill($"{profiles.Length} подключений", true));
            badges.Children.Add(Theme.Pill($"{profiles.Count(p => p.IsBridge)} мостов")); badges.Children.Add(Theme.Pill($"{profiles.Count(p => !p.IsBridge)} прямых")); card.Children.Add(badges);
            card.Children.Add(Theme.Label(AppModel.UsageText(sub.Usage), 13, Theme.Muted));
            card.Children.Add(Theme.Label(sub.Updated is DateTimeOffset updated ? "Обновлено " + updated.ToString("dd.MM · HH:mm") : "Ещё не обновлялась", 11, Theme.Muted));
            var actions = new WrapPanel();
            var refresh = Theme.Button("Обновить"); refresh.IsEnabled = !_busy; refresh.Click += async (_, _) => await RunAsync(() => _model.RefreshAsync(sub.Id, _lifetime.Token)); actions.Children.Add(refresh);
            var rename = Theme.Button("Переименовать"); rename.Click += async (_, _) => { var value = AskText("Название подписки", sub.Name); if (value != null) await RunAsync(() => _model.RenameAsync(sub.Id, value, _lifetime.Token)); }; actions.Children.Add(rename);
            var remove = Theme.Button("Удалить"); remove.Click += async (_, _) => {
                if (MessageBox.Show(this, "Удалить подписку и её подключения с этого компьютера?", "MOST", MessageBoxButton.YesNo, MessageBoxImage.Question) == MessageBoxResult.Yes)
                    await RunAsync(() => _model.RemoveAsync(sub.Id, _lifetime.Token));
            }; actions.Children.Add(remove); card.Children.Add(actions); _content.Children.Add(Theme.Card(card));
        }
        if (_model.State.Subscriptions.Count == 0) {
            var empty = new StackPanel(); empty.Children.Add(Theme.Label("Начнём с подписки", 22)); empty.Children.Add(Theme.Label("Вставьте ссылку от вашего VPN-провайдера. MOST добавит доступные страны и подключения.", 14, Theme.Muted));
            var add = Theme.Button("Добавить подписку", true); add.Click += async (_, _) => await ShowImportAsync(); empty.Children.Add(add); _content.Children.Add(Theme.Card(empty));
        }
        _content.Children.Add(Theme.Label($"Мои подключения без подписки: {_model.State.Profiles.Count(p => p.GroupId == "local")}", 12, Theme.Muted));
    }
    private void Apps()
    {
        Heading("Какие приложения работают без VPN?", "Отметьте программы, которым нужно прямое подключение к интернету.");
        _appDraft ??= _model.State.ExcludedApps.ToList();
        var card = new StackPanel(); var actions = new WrapPanel();
        var running = Theme.Button("Показать запущенные приложения"); running.IsEnabled = !_busy;
        running.Click += async (_, _) => await RunAsync(async () => { _catalog = await Task.Run(AppCatalog.Running, _lifetime.Token); }); actions.Children.Add(running);
        var add = Theme.Button("Выбрать .exe"); add.Click += (_, _) => {
            var file = new Microsoft.Win32.OpenFileDialog { Filter = "Программы Windows (*.exe)|*.exe", CheckFileExists = true, Multiselect = true };
            if (file.ShowDialog(this) == true) { _appDraft.AddRange(file.FileNames.Where(p => !_appDraft.Contains(p, StringComparer.OrdinalIgnoreCase))); Render(); }
        }; actions.Children.Add(add); card.Children.Add(actions);
        var search = Theme.TextInput(_appSearch); AutomationProperties.SetName(search, "Поиск приложения"); card.Children.Add(search);
        var appList = new StackPanel();
        var all = _catalog.Concat(_appDraft.Select(p => new DesktopApp(Path.GetFileNameWithoutExtension(p), p)))
            .DistinctBy(p => p.Path, StringComparer.OrdinalIgnoreCase).OrderByDescending(p => _appDraft.Contains(p.Path, StringComparer.OrdinalIgnoreCase)).ThenBy(p => p.Name).ToArray();
        void Populate() {
            appList.Children.Clear();
            foreach (var app in all.Where(a => a.Name.Contains(_appSearch, StringComparison.OrdinalIgnoreCase) || Path.GetFileName(a.Path).Contains(_appSearch, StringComparison.OrdinalIgnoreCase))) {
                var check = Theme.Check(app.Name + "   ·   " + Path.GetFileName(app.Path), _appDraft.Contains(app.Path, StringComparer.OrdinalIgnoreCase)); check.ToolTip = app.Path;
                check.Checked += (_, _) => { if (!_appDraft.Contains(app.Path, StringComparer.OrdinalIgnoreCase)) _appDraft.Add(app.Path); };
                check.Unchecked += (_, _) => _appDraft.RemoveAll(p => p.Equals(app.Path, StringComparison.OrdinalIgnoreCase)); appList.Children.Add(check);
            }
        }
        Populate(); search.TextChanged += (_, _) => { _appSearch = search.Text; Populate(); };
        card.Children.Add(new ScrollViewer { Content = appList, MaxHeight = 310, MinHeight = 100, VerticalScrollBarVisibility = ScrollBarVisibility.Auto });
        var save = Theme.Button("Сохранить исключения", true); save.Margin = new Thickness(0, 18, 0, 8); save.IsEnabled = !_busy;
        save.Click += async (_, _) => await RunAsync(async () => { await _model.SaveSettingsAsync(_model.State.TunMode, _appDraft, _lifetime.Token); Notice("Исключения сохранены. Переподключитесь, чтобы применить их."); }); card.Children.Add(save);
        _content.Children.Add(Theme.Card(card));
        _content.Children.Add(Theme.Label("Галочка означает «без VPN». Исключения применяются после переподключения в полном режиме VPN. Для игры и лаунчера иногда нужно отметить оба .exe.", 12, Theme.Muted));
    }
    private void Settings()
    {
        Heading("Настройки", "Сделайте MOST удобным для себя.");
        var card = new StackPanel(); card.Children.Add(Theme.Label("Режим подключения", 20));
        var tun = Theme.Check("VPN для всего компьютера", _model.State.TunMode); card.Children.Add(tun);
        card.Children.Add(Theme.Label("Приложения используют VPN, кроме ваших исключений. Если выключить, останется локальный прокси для ручной настройки программ.", 13, Theme.Muted));
        var save = Theme.Button("Сохранить режим", true); save.Click += async (_, _) => await RunAsync(() => _model.SaveSettingsAsync(tun.IsChecked == true, _model.State.ExcludedApps, _lifetime.Token)); card.Children.Add(save);
        card.Children.Add(Theme.Label("Изменение режима применяется после переподключения.", 12, Theme.Muted));
        if (_model.Connection.ProxyAddress.Length > 0) card.Children.Add(Theme.Label(_model.Connection.ProxyAddress, 12, Theme.Muted)); _content.Children.Add(Theme.Card(card));
        var sites = Theme.Button("Сайты без VPN →"); sites.Click += (_, _) => Navigate(7); _content.Children.Add(sites);
        var info = new StackPanel(); info.Children.Add(Theme.Label("MOST · " + AppModel.Version, 20)); info.Children.Add(Theme.Label("Связь без границ", 14, Theme.Accent));
        info.Children.Add(Theme.Label("Ваши подписки и настройки сохраняются на компьютере и защищаются вашей учётной записью Windows.", 13, Theme.Muted));
        var update = Theme.Button("Обновления MOST →"); update.Click += (_, _) => Navigate(6); info.Children.Add(update); _content.Children.Add(Theme.Card(info));
    }
    private void Sites()
    {
        Heading("Сайты без VPN", "Выберите домены для прямого подключения. Остальные сайты сохраняют обычный маршрут VPN.");
        var policy = _model.State.SiteRouting;
        var panel = new StackPanel();
        var ru = Theme.Check("Все сайты .ru без VPN", _siteRu ?? policy.RuDirect);
        var rf = Theme.Check("Все сайты .рф без VPN", _siteRf ?? policy.RfDirect);
        var su = Theme.Check("Все сайты .su без VPN", _siteSu ?? policy.SuDirect);
        ru.Checked += (_, _) => _siteRu = true; ru.Unchecked += (_, _) => _siteRu = false;
        rf.Checked += (_, _) => _siteRf = true; rf.Unchecked += (_, _) => _siteRf = false;
        su.Checked += (_, _) => _siteSu = true; su.Unchecked += (_, _) => _siteSu = false;
        panel.Children.Add(ru); panel.Children.Add(rf); panel.Children.Add(su);
        panel.Children.Add(Theme.Label("Дополнительные сайты без VPN", 17));
        var direct = Theme.TextInput(_siteDirectDraft ?? string.Join(Environment.NewLine, policy.DirectDomains), true); direct.MinHeight = 95;
        direct.TextChanged += (_, _) => _siteDirectDraft = direct.Text;
        AutomationProperties.SetName(direct, "Дополнительные сайты без VPN"); panel.Children.Add(direct);
        panel.Children.Add(Theme.Label("Всегда через VPN", 17));
        var proxy = Theme.TextInput(_siteProxyDraft ?? string.Join(Environment.NewLine, policy.ProxyDomains), true); proxy.MinHeight = 95;
        proxy.TextChanged += (_, _) => _siteProxyDraft = proxy.Text;
        AutomationProperties.SetName(proxy, "Всегда через VPN"); panel.Children.Add(proxy);
        panel.Children.Add(Theme.Label("По одному домену или адресу на строку. Поддомены включены. «Всегда через VPN» перекрывает .ru и список сайтов без VPN. Исключение всего приложения действует раньше правил сайтов.", 12, Theme.Muted));
        var save = Theme.Button("Сохранить правила", true); save.IsEnabled = !_busy;
        save.Click += async (_, _) => await RunAsync(async () => {
            await _model.SaveSitesAsync(ru.IsChecked == true, rf.IsChecked == true, su.IsChecked == true, direct.Text, proxy.Text, _lifetime.Token);
            _siteRu = _siteRf = _siteSu = null; _siteDirectDraft = _siteProxyDraft = null;
            Notice("Правила сохранены. Переподключите VPN и обновите открытые вкладки, чтобы применить их.");
        }); panel.Children.Add(save); _content.Children.Add(Theme.Card(panel));
        _content.Children.Add(Theme.Label("Для предсказуемой работы правил используйте системный DNS в браузере. Собственный защищённый DNS и ECH могут скрывать домен: если MOST не распознаёт сайт, он остаётся на обычном маршруте. Изменения применяются после переподключения.", 12, Theme.Muted));
    }
    private void Logs()
    {
        Heading("Журнал подключения", "Здесь можно посмотреть сообщения о соединении и скопировать их для поддержки.");
        var box = Theme.TextInput(_model.Connection.Logs, true); box.IsReadOnly = true; box.Height = 360; box.FontFamily = new FontFamily("Consolas"); box.FontSize = 12;
        _content.Children.Add(box); var actions = new WrapPanel();
        var refresh = Theme.Button("Обновить"); refresh.Click += (_, _) => box.Text = _model.Connection.Logs; actions.Children.Add(refresh);
        var copy = Theme.Button("Скопировать"); copy.Click += (_, _) => { Clipboard.SetText(_model.Connection.Logs.Length == 0 ? "Журнал пуст" : _model.Connection.Logs); Notice("Журнал скопирован"); }; actions.Children.Add(copy); _content.Children.Add(actions);
    }
    private void Updates()
    {
        Heading("Всегда свежий MOST", "Новые возможности и улучшения — прямо в приложении.");
        var box = new StackPanel(); box.Children.Add(Theme.Pill("УСТАНОВЛЕНО · " + AppModel.Version, true));
        var available = _model.Updates.Available;
        box.Children.Add(Theme.Label(available == null ? "Обновления MOST" : "MOST " + available.Version, 24));
        _updateDetail = Theme.Label("", 14, Theme.Muted); box.Children.Add(_updateDetail);
        _progress = new ProgressBar { Minimum = 0, Maximum = 100, Height = 5, Foreground = Theme.Accent, Background = Theme.Input, Margin = new Thickness(0, 12, 0, 18) }; box.Children.Add(_progress);
        var actions = new WrapPanel(); _checkUpdate = Theme.Button("Проверить обновления");
        _checkUpdate.Click += async (_, _) => await _model.Updates.CheckAsync(_lifetime.Token); actions.Children.Add(_checkUpdate);
        _downloadUpdate = Theme.Button("Скачать обновление", true); _downloadUpdate.Click += async (_, _) => await _model.Updates.DownloadAsync(_lifetime.Token); actions.Children.Add(_downloadUpdate);
        _installUpdate = Theme.Button("Установить и закрыть MOST", true);
        _installUpdate.Click += async (_, _) => await RunAsync(async () => { await _model.StopAsync(); await _model.Updates.StartInstallerAsync(_lifetime.Token); Close(); }); actions.Children.Add(_installUpdate);
        box.Children.Add(actions);
        box.Children.Add(Theme.Label("Перед установкой VPN отключится, а MOST закроется. Подписки и настройки сохранятся. Windows откроет обычный установщик.", 12, Theme.Muted));
        _content.Children.Add(Theme.Card(box));
        if (available != null) { var notes = new StackPanel(); notes.Children.Add(Theme.Label("Что нового", 20)); notes.Children.Add(Theme.Label(available.Notes, 14, Theme.Muted)); _content.Children.Add(Theme.Card(notes)); }
        var preference = new StackPanel(); var auto = Theme.Check("Сообщать о новых версиях", _model.State.CheckUpdates);
        auto.Checked += async (_, _) => { if (!_rendering) await RunAsync(() => _model.UpdatePreferencesAsync(true, _lifetime.Token)); };
        auto.Unchecked += async (_, _) => { if (!_rendering) await RunAsync(() => _model.UpdatePreferencesAsync(false, _lifetime.Token)); }; preference.Children.Add(auto);
        preference.Children.Add(Theme.Label("Проверяем при запуске и примерно раз в 6 часов, пока MOST открыт или свёрнут в трей. Загрузка и установка начинаются по вашему нажатию.", 12, Theme.Muted));
        _content.Children.Add(Theme.Card(preference));
    }
    private async Task ObserveUpdatesAsync(CancellationToken ct)
    {
        try {
            while (true) {
                if (_model.State.CheckUpdates) {
                    await _model.Updates.CheckAsync(ct);
                    if (_model.Updates.Available is DesktopUpdate update && _model.State.NotifiedUpdate != update.Version && !ct.IsCancellationRequested) {
                        await _model.MarkUpdateNotifiedAsync(update.Version, ct);
                        _tray.ShowBalloonTip(6000, "Доступна MOST " + update.Version, "Посмотрите, что нового, и обновите приложение.", Forms.ToolTipIcon.Info);
                    }
                }
                await Task.Delay(TimeSpan.FromHours(6), ct);
            }
        } catch (OperationCanceledException) { }
        catch (Exception) { /* Automatic checks must never interrupt the VPN or block the window. */ }
    }
    private async Task ShowImportAsync()
    {
        if (_busy) return;
        var dialog = new Window { Owner = this, Title = "Добавить в MOST", Width = 650, Height = 580,
            MinHeight = 440, Background = Theme.Background, Foreground = Theme.Text, WindowStartupLocation = WindowStartupLocation.CenterOwner };
        var panel = new StackPanel { Margin = new Thickness(25) }; dialog.Content = new ScrollViewer { Content = panel, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
        panel.Children.Add(Theme.Label("Добавить подписку", 24));
        panel.Children.Add(Theme.Label("Вставьте ссылку HTTP/HTTPS от вашего провайдера. Можно также добавить VLESS-ссылки или конфигурацию Xray.", 13, Theme.Muted));
        var text = Theme.TextInput("", true); text.MinHeight = 140; AutomationProperties.SetName(text, "Ссылка или конфигурация"); panel.Children.Add(text);
        var paste = Theme.Button("Вставить из буфера"); paste.Click += (_, _) => text.Text = Clipboard.ContainsText() ? Clipboard.GetText() : ""; panel.Children.Add(paste);
        var name = Theme.TextInput(); AutomationProperties.SetName(name, "Название подписки"); panel.Children.Add(Theme.Label("Как назовём? (необязательно)", 13, Theme.Muted)); panel.Children.Add(name);
        var http = Theme.Check("Разрешить обычный HTTP для этой подписки", false); panel.Children.Add(http);
        panel.Children.Add(Theme.Label("HTTP передаёт ссылку и содержимое подписки без шифрования.", 12, Theme.Muted));
        var add = Theme.Button("Добавить", true); add.Click += (_, _) => dialog.DialogResult = true; panel.Children.Add(add);
        if (dialog.ShowDialog() == true) await RunAsync(async () => { await _model.ImportAsync(text.Text, name.Text, http.IsChecked == true, _lifetime.Token); _page = 0; Render(); });
    }
    private string? AskText(string title, string value)
    {
        var dialog = new Window { Owner = this, Title = title, Width = 440, Height = 250, Background = Theme.Background, Foreground = Theme.Text, WindowStartupLocation = WindowStartupLocation.CenterOwner, ResizeMode = ResizeMode.NoResize };
        var panel = new StackPanel { Margin = new Thickness(22) }; panel.Children.Add(Theme.Label(title, 20));
        var text = Theme.TextInput(value); panel.Children.Add(text); var save = Theme.Button("Сохранить", true); save.Click += (_, _) => dialog.DialogResult = true; panel.Children.Add(save);
        dialog.Content = new ScrollViewer { Content = panel, VerticalScrollBarVisibility = ScrollBarVisibility.Auto }; return dialog.ShowDialog() == true ? text.Text : null;
    }
}
