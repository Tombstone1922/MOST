using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;

namespace Most.Windows.Core;

/// Owns durable desktop state and subscription transactions; views only dispatch actions.
public sealed class AppModel
{
    public const string Version = "0.3.0";
    private readonly Storage _storage = new();
    private readonly SemaphoreSlim _data = new(1, 1);
    public bool Initialized { get; private set; }
    public AppState State { get; private set; } = new();
    public ConnectionController Connection { get; } = new();
    private CancellationTokenSource? _routeSession;
    private Task? _recovery;
    private long? _appliedSettingsRevision;
    private string? _appliedSelection;
    public bool RulesPending => Connection.HasSession && _appliedSettingsRevision.HasValue && _appliedSettingsRevision != State.ConnectionSettingsRevision;
    public bool RoutePending => Connection.Status.Active && _appliedSelection != null && _appliedSelection != SelectionKey(State);
    public static string SelectionKey(AppState state) => state.AutoCountry
        ? $"auto:{state.AutoGroupId}:{state.AutoCountryCode}:{state.AutoDirect}"
        : "manual:" + state.SelectedId;
    public UpdateManager Updates { get; } = new();
    public event Action? Changed;
    public async Task InitializeAsync(CancellationToken ct)
    {
        State = await Task.Run(_storage.Load, ct); Initialized = true;
        await Task.Run(() => {
            var path = Path.Combine(Storage.Root, "runtime");
            if (Directory.Exists(path)) foreach (var dir in Directory.EnumerateDirectories(path)) {
                try { Directory.Delete(dir, true); } catch (IOException) { } catch (UnauthorizedAccessException) { }
            }
        }, ct);
        Changed?.Invoke();
    }
    public async Task ImportAsync(string input, string name, bool allowHttp, CancellationToken ct)
    {
        RequireInitialized();
        input = input.Trim();
        if (input.Length == 0) throw new UserError("Вставьте ссылку подписки или VLESS-подключения.");
        await _data.WaitAsync(ct);
        try {
            var draft = Storage.Clone(State);
            if (input.StartsWith("https://", StringComparison.OrdinalIgnoreCase) || input.StartsWith("http://", StringComparison.OrdinalIgnoreCase)) {
                var existing = draft.Subscriptions.FirstOrDefault(s => s.Url == input);
                var sub = existing ?? new Subscription { Url = input };
                var response = await SubscriptionClient.FetchAsync(input, allowHttp, draft.DeviceId, ct);
                var profiles = await Task.Run(() => ProfileParser.Parse(response.Content, sub.Id), ct);
                sub.Name = CleanName(name.Length > 0 ? name : (existing?.Name ?? new Uri(input).Host));
                sub.AllowHttp = allowHttp; sub.Updated = DateTimeOffset.Now; sub.Usage = response.Usage;
                draft.Profiles.RemoveAll(p => p.GroupId == sub.Id); draft.Profiles.AddRange(profiles);
                if (existing == null) draft.Subscriptions.Add(sub);
                draft.GroupFilter = sub.Id;
                if (draft.AutoGroupId != sub.Id) draft.AutoCountry = false;
            } else {
                var profiles = await Task.Run(() => ProfileParser.Parse(input, "local"), ct);
                var known = draft.Profiles.Select(p => p.Id).ToHashSet();
                draft.Profiles.AddRange(profiles.Where(p => known.Add(p.Id))); draft.GroupFilter = "local"; draft.AutoCountry = false;
            }
            var selected = draft.Profiles.FirstOrDefault(p => p.GroupId == draft.GroupFilter && p.Id == draft.SelectedId)
                ?? draft.Profiles.FirstOrDefault(p => p.GroupId == draft.GroupFilter);
            if (selected != null) { draft.SelectedId = selected.Id; draft.DirectRoutes = !selected.IsBridge; draft.CountryFilter = ""; }
            await CommitAsync(draft, ct);
        } finally { _data.Release(); }
    }
    public async Task RefreshAsync(string groupId, CancellationToken ct)
    {
        var sub = State.Subscriptions.FirstOrDefault(s => s.Id == groupId) ?? throw new UserError("Подписка не найдена.");
        await ImportAsync(sub.Url, sub.Name, sub.AllowHttp, ct);
    }
    public async Task SelectAsync(string id, CancellationToken ct) => await EditAsync(s => {
        if (s.Profiles.All(p => p.Id != id)) throw new UserError("Подключение больше не существует. Обновите список.");
        s.SelectedId = id; s.AutoCountry = false;
    }, ct);
    public async Task FiltersAsync(string group, string country, bool direct, CancellationToken ct) =>
        await EditAsync(s => { s.GroupFilter = group; s.CountryFilter = country; s.DirectRoutes = direct; }, ct);
    public async Task SaveSettingsAsync(bool tun, IEnumerable<string> excluded, CancellationToken ct) => await EditAsync(s => {
        var paths = excluded.Where(p => Path.IsPathFullyQualified(p) && p.EndsWith(".exe", StringComparison.OrdinalIgnoreCase))
            .Distinct(StringComparer.OrdinalIgnoreCase).ToList();
        if (s.TunMode != tun || !s.ExcludedApps.SequenceEqual(paths, StringComparer.OrdinalIgnoreCase)) s.ConnectionSettingsRevision++;
        s.TunMode = tun; s.ExcludedApps = paths;
    }, ct);
    public async Task RenameAsync(string id, string name, CancellationToken ct) => await EditAsync(s => {
        var sub = s.Subscriptions.FirstOrDefault(x => x.Id == id) ?? throw new UserError("Подписка не найдена."); sub.Name = CleanName(name);
    }, ct);
    public async Task RemoveAsync(string id, CancellationToken ct)
    {
        if (Connection.HasSession && Connection.Status.Profile?.GroupId == id) await StopAsync();
        await EditAsync(s => {
            s.Subscriptions.RemoveAll(x => x.Id == id); s.Profiles.RemoveAll(p => p.GroupId == id);
            if (s.Selected == null) s.SelectedId = s.Profiles.FirstOrDefault()?.Id ?? "";
            if (s.GroupFilter == id) s.GroupFilter = "";
            if (s.AutoGroupId == id) s.AutoCountry = false;
        }, ct);
    }
    public async Task ChooseCountryAsync(string group, string country, bool direct, CancellationToken ct) => await EditAsync(s => {
        var candidates = RoutePolicy.Candidates(s, group, country, direct);
        if (candidates.Count == 0) throw new UserError("Для этой страны нет подходящих подключений в выбранной подписке.");
        s.AutoCountry = true; s.AutoGroupId = group; s.AutoCountryCode = country; s.AutoDirect = direct;
        s.SelectedId = candidates[0].Id; s.GroupFilter = group; s.CountryFilter = country; s.DirectRoutes = direct;
    }, ct);
    public Task SaveSitesAsync(bool ru, bool rf, bool su, string direct, string proxy, CancellationToken ct) => EditAsync(s => {
        var policy = new SiteRouting { RuDirect = ru, RfDirect = rf, SuDirect = su,
            DirectDomains = SiteRouting.Parse(direct), ProxyDomains = SiteRouting.Parse(proxy) };
        if (System.Text.Json.JsonSerializer.Serialize(policy, Storage.Json) != System.Text.Json.JsonSerializer.Serialize(s.SiteRouting, Storage.Json)) s.ConnectionSettingsRevision++;
        s.SiteRouting = policy;
    }, ct);
    public Task FavoriteAsync(string id, CancellationToken ct) => EditAsync(s => {
        if (s.Profiles.All(p => p.Id != id)) throw new UserError("Подключение больше не существует.");
        if (!s.Favorites.Remove(id)) s.Favorites.Add(id);
    }, ct);
    public Task UpdatePreferencesAsync(bool enabled, CancellationToken ct) => EditAsync(s => s.CheckUpdates = enabled, ct);
    public Task MarkUpdateNotifiedAsync(string version, CancellationToken ct) => EditAsync(s => s.NotifiedUpdate = version, ct);
    public async Task ConnectAsync(CancellationToken ct)
    {
        RequireInitialized();
        await StopAsync(); ct.ThrowIfCancellationRequested();
        var snapshot = Storage.Clone(State);
        _routeSession = CancellationTokenSource.CreateLinkedTokenSource(ct);
        var token = _routeSession.Token;
        if (!snapshot.AutoCountry) {
            var profile = snapshot.Selected ?? throw new UserError("Сначала выберите страну или сервер.");
            await Connection.ConnectAsync(profile, snapshot, token);
            _appliedSettingsRevision = snapshot.ConnectionSettingsRevision; _appliedSelection = SelectionKey(snapshot);
            return;
        }
        await TryCountryAsync(snapshot, "", token);
        _appliedSettingsRevision = snapshot.ConnectionSettingsRevision; _appliedSelection = SelectionKey(snapshot);
        _recovery = RecoverCountryAsync(snapshot, token);
    }
    private async Task TryCountryAsync(AppState snapshot, string failedId, CancellationToken ct)
    {
        var candidates = RoutePolicy.Candidates(snapshot, snapshot.AutoGroupId, snapshot.AutoCountryCode, snapshot.AutoDirect, failedId);
        if (candidates.Count == 0) throw new UserError("Для выбранной страны нет подключений. Обновите подписку или выберите другую страну.");
        foreach (var profile in candidates) {
            ct.ThrowIfCancellationRequested();
            try {
                await Connection.ConnectAsync(profile, snapshot, ct);
                var result = Connection.Status;
                // Automatic mode must verify the requested exit; manual selection can show an unknown/mismatched exit.
                if (result.Country.Length == 0 || !RoutePolicy.ExitMatches(profile, result.Country)) {
                    await Connection.StopAsync(); continue;
                }
                snapshot.LastWorking[RoutePolicy.CacheKey(profile)] = profile.Id;
                await EditAsync(s => { s.LastWorking[RoutePolicy.CacheKey(profile)] = profile.Id; }, ct);
                return;
            } catch (OperationCanceledException) { throw; }
            catch (UserError) { }
            catch (System.Net.Http.HttpRequestException) { }
        }
        throw new UserError("Не удалось подключиться к выбранной стране. Попробуйте другую страну или выберите сервер вручную.");
    }
    private async Task RecoverCountryAsync(AppState snapshot, CancellationToken ct)
    {
        try {
            while (true) {
                await Task.Delay(TimeSpan.FromSeconds(4), ct);
                var status = Connection.Status;
                if (status.Busy || (status.Active && status.Country.Length > 0 && status.Profile != null && RoutePolicy.ExitMatches(status.Profile, status.Country))) continue;
                await TryCountryAsync(snapshot, status.Profile?.Id ?? "", ct);
            }
        } catch (OperationCanceledException) { }
        catch (Exception e) {
            if (!ct.IsCancellationRequested) Connection.ReportUnavailable(e is UserError ? e.Message : "Не удалось восстановить соединение. Нажмите «Подключить», чтобы попробовать снова.");
        }
    }
    public async Task StopAsync()
    {
        _routeSession?.Cancel();
        if (_recovery != null) { await _recovery; _recovery = null; }
        await Connection.StopAsync();
        _routeSession?.Dispose(); _routeSession = null;
        _appliedSettingsRevision = null; _appliedSelection = null;
    }
    private async Task EditAsync(Action<AppState> edit, CancellationToken ct)
    {
        RequireInitialized();
        await _data.WaitAsync(ct);
        try { var draft = Storage.Clone(State); edit(draft); await CommitAsync(draft, ct); }
        finally { _data.Release(); }
    }
    private void RequireInitialized() { if (!Initialized) throw new UserError("Хранилище настроек не открыто. Перезапустите MOST; существующий файл не перезаписывается."); }
    private async Task CommitAsync(AppState state, CancellationToken ct)
    {
        ct.ThrowIfCancellationRequested(); await Task.Run(() => _storage.Save(state), ct);
        State = state; Changed?.Invoke();
    }
    public static string CleanName(string name)
    {
        name = name.Trim();
        if (name.Length is < 1 or > 80 || name.Any(char.IsControl)) throw new UserError("Название: от 1 до 80 символов, без переноса строки.");
        return name;
    }
    public static string UsageText(string raw)
    {
        try {
            var fields = raw.Split(';', StringSplitOptions.RemoveEmptyEntries).Select(p => p.Trim().Split('=', 2))
                .Where(p => p.Length == 2).ToDictionary(p => p[0], p => long.Parse(p[1]));
            var used = fields.GetValueOrDefault("upload") + fields.GetValueOrDefault("download");
            var total = fields.GetValueOrDefault("total"); var expires = fields.GetValueOrDefault("expire");
            var result = $"Использовано: {used / 1073741824.0:F1} ГБ";
            if (total > 0) result += $" из {total / 1073741824.0:F1} ГБ";
            if (expires > 0) result += " · до " + DateTimeOffset.FromUnixTimeSeconds(expires).ToLocalTime().ToString("dd.MM.yyyy");
            return raw.Length == 0 ? "Срок и трафик сервером не переданы" : result;
        } catch { return "Сервер не передал читаемые данные о сроке и трафике"; }
    }
}
