using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;

namespace Most.Windows.Core;

public sealed record ConnectionStatus(bool Active, bool Busy, string Title, string Detail, string Ip = "", string Country = "", Profile? Profile = null);
public sealed class ConnectionController
{
    private readonly SemaphoreSlim _gate = new(1, 1);
    private CancellationTokenSource? _session;
    private Process? _process;
    private CoreJob? _job;
    private Task? _monitor;
    private Task[] _readers = Array.Empty<Task>();
    private string? _folder;
    private int _httpPort, _socksPort;
    private long _revision;
    private readonly Queue<string> _logs = new();
    private readonly object _logsLock = new();
    public event Action<ConnectionStatus>? Changed;
    public ConnectionStatus Status { get; private set; } = new(false, false, "VPN выключен", "Добавьте подписку и выберите подключение.");
    public DateTimeOffset? ConnectedSince { get; private set; }
    public int? LatencyMs { get; private set; }
    public void ReportUnavailable(string detail) => Publish(new(false, false, "Не удалось подключиться", detail));
    public bool HasSession => _session != null;
    public string ProxyAddress => _httpPort == 0 ? "" : $"HTTP 127.0.0.1:{_httpPort} · SOCKS 127.0.0.1:{_socksPort}";
    public string Logs { get { lock (_logsLock) return string.Join(Environment.NewLine, _logs); } }
    private void Publish(ConnectionStatus value) { Status = value; Changed?.Invoke(value); }
    private void Log(string line)
    {
        if (Status.Profile is Profile profile) line = line.Replace(profile.Uuid, "[UUID скрыт]", StringComparison.OrdinalIgnoreCase);
        line = Regex.Replace(line, @"(?:vless|https?|vmess|trojan)://\S+", "[адрес скрыт]", RegexOptions.IgnoreCase);
        line = Regex.Replace(line, @"\b[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\b", "[UUID скрыт]", RegexOptions.IgnoreCase);
        lock (_logsLock) { _logs.Enqueue(line.Length > 600 ? line[..600] : line); while (_logs.Count > 100) _logs.Dequeue(); }
    }
    public async Task ConnectAsync(Profile profile, AppState state, CancellationToken lifetime)
    {
        var revision = Interlocked.Increment(ref _revision);
        _session?.Cancel();
        await _gate.WaitAsync(lifetime);
        try {
            await StopInsideAsync();
            if (revision != Interlocked.Read(ref _revision)) throw new OperationCanceledException();
            _session = CancellationTokenSource.CreateLinkedTokenSource(lifetime);
            var ct = _session.Token;
            Publish(new(false, true, "Подключение…", "Проверка компонентов и настройка сети.", Profile: profile));
            await NativeCore.VerifyAsync(ct);
            _httpPort = FreePort(); _socksPort = FreePort();
            while (_socksPort == _httpPort) _socksPort = FreePort();
            _folder = Path.Combine(Storage.Root, "runtime", Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(_folder);
            var configPath = Path.Combine(_folder, "session.json");
            var config = ConfigBuilder.Build(profile, state, _httpPort, _socksPort, Socket.OSSupportsIPv6);
            await File.WriteAllTextAsync(configPath, config.ToJsonString(), ct);
            _job = new CoreJob();
            using (var test = NativeCore.Start(configPath, true)) {
                _job.Add(test);
                var output = test.StandardOutput.ReadToEndAsync(ct); var error = test.StandardError.ReadToEndAsync(ct);
                try { await test.WaitForExitAsync(ct).WaitAsync(TimeSpan.FromSeconds(15), ct); }
                finally { if (!test.HasExited) { test.Kill(true); await test.WaitForExitAsync(CancellationToken.None); } }
                var diagnostic = await output + await error;
                if (test.ExitCode != 0) { Log(diagnostic); throw new UserError("Xray отклонил настройки подключения. Подробности в журнале."); }
            }
            ct.ThrowIfCancellationRequested();
            if (revision != Interlocked.Read(ref _revision)) throw new OperationCanceledException();
            _process = NativeCore.Start(configPath, false); _job.Add(_process);
            _readers = new[] { ReadLogAsync(_process.StandardOutput, ct), ReadLogAsync(_process.StandardError, ct) };
            var deadline = DateTime.UtcNow.AddSeconds(22);
            while (true) {
                ct.ThrowIfCancellationRequested();
                if (_process.HasExited) throw new UserError("Ядро Xray остановилось при запуске. Проверьте журнал и разрешение администратора.");
                var ready = !state.TunMode || await Task.Run(() => NetworkInterface.GetAllNetworkInterfaces().Any(n =>
                    n.Name == ConfigBuilder.InterfaceName && n.OperationalStatus == OperationalStatus.Up &&
                    n.GetIPProperties().UnicastAddresses.Any(a => a.Address.ToString() == "172.29.217.1")), ct);
                if (ready && await PortOpenAsync(_httpPort, ct)) break;
                if (DateTime.UtcNow >= deadline) throw new UserError("Windows не смог подготовить VPN-интерфейс. Закройте другой VPN и проверьте журнал MOST.");
                await Task.Delay(200, ct);
            }
            var health = await ProbeAsync(profile, ct);
            ct.ThrowIfCancellationRequested();
            if (revision != Interlocked.Read(ref _revision)) throw new OperationCanceledException();
            if (!health.Active) throw new UserError(health.Detail);
            ConnectedSince = DateTimeOffset.Now; Publish(health);
            _monitor = MonitorAsync(_process, profile, revision, ct);
        } catch (OperationCanceledException) {
            await StopInsideAsync(); Publish(new(false, false, "VPN выключен", "Подключение отменено.")); throw;
        } catch {
            await StopInsideAsync(); Publish(new(false, false, "Не удалось подключиться", "Выберите другое подключение или откройте журнал.")); throw;
        } finally { _gate.Release(); }
    }
    public async Task StopAsync()
    {
        Interlocked.Increment(ref _revision); _session?.Cancel();
        await _gate.WaitAsync();
        try { await StopInsideAsync(); Publish(new(false, false, "VPN выключен", "Соединение завершено.")); }
        finally { _gate.Release(); }
    }
    private async Task StopInsideAsync()
    {
        ConnectedSince = null; LatencyMs = null;
        _session?.Cancel();
        if (_monitor != null) { try { await _monitor; } catch (OperationCanceledException) { } _monitor = null; }
        if (_process != null) {
            try {
                if (!_process.HasExited) {
                    NativeCore.RequestGracefulStop();
                    try { await _process.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(6)); }
                    catch (TimeoutException) { _process.Kill(true); await _process.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(3)); }
                }
            } catch (Exception error) { Log("Завершение ядра: " + error.GetType().Name); }
            finally { _job?.Dispose(); _job = null; _process.Dispose(); _process = null; }
        }
        _job?.Dispose(); _job = null;
        foreach (var reader in _readers) { try { await reader; } catch (OperationCanceledException) { } catch (IOException) { } catch (ObjectDisposedException) { } }
        _readers = Array.Empty<Task>();
        _session?.Dispose(); _session = null; _httpPort = _socksPort = 0;
        if (_folder != null) { try { Directory.Delete(_folder, true); } catch (IOException) { } _folder = null; }
    }
    private async Task ReadLogAsync(StreamReader reader, CancellationToken ct)
    {
        try { while (await reader.ReadLineAsync(ct) is string line) Log(line); }
        catch (OperationCanceledException) { } catch (IOException) { } catch (ObjectDisposedException) { }
    }
    private async Task MonitorAsync(Process process, Profile profile, long revision, CancellationToken ct)
    {
        try {
            while (true) {
                await Task.Delay(TimeSpan.FromSeconds(25), ct);
                if (process.HasExited) { if (revision == Interlocked.Read(ref _revision)) Publish(new(false, false, "Соединение остановилось", "Ядро Xray завершилось. Откройте журнал и переподключитесь.", Profile: profile)); return; }
                var result = await ProbeAsync(profile, ct);
                ct.ThrowIfCancellationRequested();
                if (revision == Interlocked.Read(ref _revision)) Publish(result);
            }
        } catch (OperationCanceledException) { }
        catch (Exception e) { Log("Проверка соединения: " + e.GetType().Name); }
    }
    private async Task<ConnectionStatus> ProbeAsync(Profile profile, CancellationToken ct)
    {
        using var handler = new SocketsHttpHandler { Proxy = new WebProxy($"http://127.0.0.1:{_httpPort}"), UseProxy = true,
            ConnectTimeout = TimeSpan.FromSeconds(5), AllowAutoRedirect = false };
        using var client = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(9) };
        try {
            var timer = Stopwatch.StartNew();
            var text = await client.GetStringAsync("https://www.cloudflare.com/cdn-cgi/trace", ct);
            if (text.Length > 65536) throw new HttpRequestException();
            var data = text.Split('\n').Where(s => s.Contains('=')).Select(s => s.Split('=', 2)).ToDictionary(s => s[0], s => s[1]);
            if (data.TryGetValue("ip", out var ip) && IPAddress.TryParse(ip, out _)) {
                LatencyMs = (int)timer.ElapsedMilliseconds;
                var country = data.GetValueOrDefault("loc", "").Trim();
                var matches = RoutePolicy.ExitMatches(profile, country);
                return new(true, false, matches ? "VPN подключён" : "Выход в другой стране",
                    matches ? "Доступ через выбранное подключение подтверждён." : $"Выбрано: {RoutePolicy.CountryName(profile.Country)}. Фактический выход: {country}. Проверьте маршрут на сервере.",
                    ip, country, profile);
            }
        } catch (OperationCanceledException) when (!ct.IsCancellationRequested) { }
        catch (HttpRequestException) { }
        ct.ThrowIfCancellationRequested();
        try {
            using var response = await client.GetAsync("https://www.gstatic.com/generate_204", ct);
            if (response.StatusCode == HttpStatusCode.NoContent) return new(true, false, "VPN подключён", "Интернет через VPN доступен. Определить IP и страну сейчас не удалось.", Profile: profile);
        } catch (OperationCanceledException) when (!ct.IsCancellationRequested) { }
        catch (HttpRequestException) { }
        ct.ThrowIfCancellationRequested();
        return new(false, false, "Нет доступа через VPN", "Не удалось открыть интернет через выбранный профиль. Проверьте сеть и сервер или выберите другой профиль.", Profile: profile);
    }
    private static int FreePort() { var listener = new TcpListener(IPAddress.Loopback, 0); listener.Start(); var p = ((IPEndPoint)listener.LocalEndpoint).Port; listener.Stop(); return p; }
    private static async Task<bool> PortOpenAsync(int port, CancellationToken ct)
    {
        try { using var client = new TcpClient(); await client.ConnectAsync(IPAddress.Loopback, port, ct); return true; }
        catch (SocketException) { return false; }
    }
}
