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
    public const string Version = "0.1.0";
    private readonly Storage _storage = new();
    private readonly SemaphoreSlim _data = new(1, 1);
    public bool Initialized { get; private set; }
    public AppState State { get; private set; } = new();
    public ConnectionController Connection { get; } = new();
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
            } else {
                var profiles = await Task.Run(() => ProfileParser.Parse(input, "local"), ct);
                var known = draft.Profiles.Select(p => p.Id).ToHashSet();
                draft.Profiles.AddRange(profiles.Where(p => known.Add(p.Id))); draft.GroupFilter = "local";
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
        s.SelectedId = id;
    }, ct);
    public async Task FiltersAsync(string group, string country, bool direct, CancellationToken ct) =>
        await EditAsync(s => { s.GroupFilter = group; s.CountryFilter = country; s.DirectRoutes = direct; }, ct);
    public async Task SaveSettingsAsync(bool tun, IEnumerable<string> excluded, CancellationToken ct) => await EditAsync(s => {
        s.TunMode = tun;
        s.ExcludedApps = excluded.Where(p => Path.IsPathFullyQualified(p) && p.EndsWith(".exe", StringComparison.OrdinalIgnoreCase))
            .Distinct(StringComparer.OrdinalIgnoreCase).ToList();
    }, ct);
    public async Task RenameAsync(string id, string name, CancellationToken ct) => await EditAsync(s => {
        var sub = s.Subscriptions.FirstOrDefault(x => x.Id == id) ?? throw new UserError("Подписка не найдена."); sub.Name = CleanName(name);
    }, ct);
    public async Task RemoveAsync(string id, CancellationToken ct)
    {
        if (Connection.HasSession && Connection.Status.Profile?.GroupId == id) await Connection.StopAsync();
        await EditAsync(s => {
            s.Subscriptions.RemoveAll(x => x.Id == id); s.Profiles.RemoveAll(p => p.GroupId == id);
            if (s.Selected == null) s.SelectedId = s.Profiles.FirstOrDefault()?.Id ?? "";
            if (s.GroupFilter == id) s.GroupFilter = "";
        }, ct);
    }
    public async Task ConnectAsync(CancellationToken ct)
    {
        RequireInitialized();
        var snapshot = Storage.Clone(State);
        var profile = snapshot.Selected ?? throw new UserError("Сначала выберите подключение на вкладке «Серверы».");
        await Connection.ConnectAsync(profile, snapshot, ct);
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
