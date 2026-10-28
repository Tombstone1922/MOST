using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;

namespace Most.Windows.Core;

public sealed record DesktopUpdate(string Version, string Url, long Size, string Sha256, string Notes);

/// Owns the desktop release/download lifetime. Only the fixed MOST repository can supply installers.
public sealed class UpdateManager
{
    private const string Repository = "Tombstone1922/MOST";
    private readonly SemaphoreSlim _gate = new(1, 1);
    public DesktopUpdate? Available { get; private set; }
    public string Message { get; private set; } = "MOST проверит наличие новой версии.";
    public bool Busy { get; private set; }
    public int Progress { get; private set; }
    public string? DownloadedPath { get; private set; }
    public event Action? Changed;
    private void Notify() => Changed?.Invoke();
    private static HttpClient Client() {
        var client = new HttpClient(new SocketsHttpHandler { AllowAutoRedirect = false, ConnectTimeout = TimeSpan.FromSeconds(15) }) { Timeout = TimeSpan.FromMinutes(5) };
        client.DefaultRequestHeaders.UserAgent.ParseAdd("MOST-Windows/" + AppModel.Version);
        return client;
    }
    public static DesktopUpdate? SelectRelease(string json, string current)
    {
        using var doc = JsonDocument.Parse(json);
        DesktopUpdate? best = null;
        var currentVersion = System.Version.Parse(current);
        foreach (var release in doc.RootElement.EnumerateArray()) {
            if (release.GetProperty("draft").GetBoolean()) continue;
            var tag = release.GetProperty("tag_name").GetString() ?? "";
            var match = Regex.Match(tag, @"^windows-v(\d+\.\d+\.\d+)$");
            if (!match.Success || !System.Version.TryParse(match.Groups[1].Value, out var version) || version <= currentVersion || (best != null && version <= System.Version.Parse(best.Version))) continue;
            var name = $"MOST-Windows-{version}-Setup.exe";
            foreach (var asset in release.GetProperty("assets").EnumerateArray()) {
                if (asset.GetProperty("name").GetString() != name) continue;
                var url = asset.GetProperty("browser_download_url").GetString() ?? "";
                var size = asset.GetProperty("size").GetInt64();
                var digest = asset.TryGetProperty("digest", out var d) ? d.GetString() ?? "" : "";
                // GitHub computes an immutable SHA256 digest on upload. Missing digests cannot enable installation.
                if (url != $"https://github.com/{Repository}/releases/download/{tag}/{name}" || size is < 1048576 or > 209715200 || !Regex.IsMatch(digest, "^sha256:[0-9a-f]{64}$")) continue;
                var notes = release.TryGetProperty("body", out var body) ? body.GetString() ?? "" : "";
                best = new(version.ToString(), url, size, digest[7..], notes.Length > 12000 ? notes[..12000] : notes);
            }
        }
        return best;
    }
    public async Task CheckAsync(CancellationToken ct)
    {
        if (!await _gate.WaitAsync(0, ct)) return;
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(ct); deadline.CancelAfter(TimeSpan.FromSeconds(40));
        ct = deadline.Token;
        Busy = true; Message = "Ищем новую версию…"; Notify();
        try {
            using var client = Client();
            using var response = await GetAsync(client, new Uri($"https://api.github.com/repos/{Repository}/releases?per_page=100"), ct);
            if (response.StatusCode == HttpStatusCode.Forbidden || (int)response.StatusCode == 429) throw new UserError("GitHub временно ограничил проверки. Попробуйте позднее.");
            response.EnsureSuccessStatusCode();
            var bytes = await ReadBoundedAsync(response, 4 * 1024 * 1024, ct);
            var available = await Task.Run(() => SelectRelease(System.Text.Encoding.UTF8.GetString(bytes), AppModel.Version), ct);
            if (available?.Version != Available?.Version) DownloadedPath = null;
            Available = available; Progress = 0;
            Message = available == null ? "У вас последняя версия MOST." : $"Доступна MOST {available.Version}";
        } catch (OperationCanceledException) when (ct.IsCancellationRequested) { Message = "Проверка отменена."; }
        catch (Exception e) { Message = e is UserError ? e.Message : "Не удалось проверить обновления. Проверьте интернет и попробуйте снова."; }
        finally { Busy = false; _gate.Release(); Notify(); }
    }
    public async Task DownloadAsync(CancellationToken ct)
    {
        if (!await _gate.WaitAsync(0, ct)) return;
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(ct); deadline.CancelAfter(TimeSpan.FromMinutes(5));
        ct = deadline.Token;
        Busy = true; Progress = 0; DownloadedPath = null; Message = "Загружаем обновление…"; Notify();
        string? temporary = null;
        try {
            var update = Available ?? throw new UserError("Сначала проверьте наличие обновлений.");
            var directory = Path.Combine(Storage.Root, "updates"); Directory.CreateDirectory(directory);
            // Unpredictable filenames avoid accepting a stale or pre-created installer.
            var target = Path.Combine(directory, "MOST-" + update.Version + "-" + Guid.NewGuid().ToString("N") + ".exe");
            temporary = target + ".part";
            using var client = Client();
            using var response = await GetAsync(client, new Uri(update.Url), ct); response.EnsureSuccessStatusCode();
            if (response.Content.Headers.ContentLength is long length && length != update.Size) throw new UserError("Размер обновления не совпадает. Повторите загрузку.");
            using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
            await using (var file = new FileStream(temporary, FileMode.CreateNew, FileAccess.Write, FileShare.None, 65536, true)) {
                await using var source = await response.Content.ReadAsStreamAsync(ct);
                var buffer = new byte[65536]; long total = 0;
                int read;
                while ((read = await source.ReadAsync(buffer, ct)) > 0) {
                    total += read; if (total > update.Size) throw new UserError("Получен неверный файл обновления.");
                    hash.AppendData(buffer, 0, read); await file.WriteAsync(buffer.AsMemory(0, read), ct);
                    var percent = (int)(100 * total / update.Size);
                    if (percent != Progress) { Progress = percent; Notify(); }
                }
                var digest = Convert.ToHexString(hash.GetHashAndReset()).ToLowerInvariant();
                if (total != update.Size || digest != update.Sha256) throw new UserError("Обновление повреждено. Повторите загрузку.");
            }
            ct.ThrowIfCancellationRequested(); File.Move(temporary, target); temporary = null;
            DownloadedPath = target; Message = "Обновление готово. Установите его, когда будет удобно.";
        } catch (OperationCanceledException) when (ct.IsCancellationRequested) { Message = "Загрузка отменена."; }
        catch (Exception e) { Message = e is UserError ? e.Message : "Не удалось скачать обновление. Попробуйте снова."; }
        finally { if (temporary != null) { try { File.Delete(temporary); } catch (IOException) { } } Busy = false; _gate.Release(); Notify(); }
    }
    public async Task StartInstallerAsync(CancellationToken ct)
    {
        var update = Available ?? throw new UserError("Обновление не выбрано.");
        var path = DownloadedPath ?? throw new UserError("Сначала загрузите обновление.");
        await using (var file = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read)) {
            if (file.Length != update.Size || Convert.ToHexString(await SHA256.HashDataAsync(file, ct)).ToLowerInvariant() != update.Sha256)
                throw new UserError("Файл обновления изменился. Загрузите его заново.");
            ct.ThrowIfCancellationRequested();
            _ = Process.Start(new ProcessStartInfo(path) { UseShellExecute = true }) ?? throw new UserError("Не удалось открыть установщик.");
        }
    }
    private static bool Trusted(Uri uri) => uri.Scheme == "https" && uri.IsDefaultPort && uri.UserInfo.Length == 0 &&
        uri.Host is "api.github.com" or "github.com" or "release-assets.githubusercontent.com" or "objects.githubusercontent.com" or "github-releases.githubusercontent.com";
    private static async Task<HttpResponseMessage> GetAsync(HttpClient client, Uri uri, CancellationToken ct)
    {
        for (var i = 0; i < 6; i++) {
            if (!Trusted(uri)) throw new UserError("Неожиданный адрес обновления. Загрузка остановлена.");
            var response = await client.GetAsync(uri, HttpCompletionOption.ResponseHeadersRead, ct);
            if ((int)response.StatusCode is >= 300 and < 400 && response.Headers.Location is Uri location) {
                uri = location.IsAbsoluteUri ? location : new Uri(uri, location); response.Dispose(); continue;
            }
            return response;
        }
        throw new UserError("Слишком много перенаправлений при загрузке обновления.");
    }
    private static async Task<byte[]> ReadBoundedAsync(HttpResponseMessage response, int limit, CancellationToken ct)
    {
        await using var source = await response.Content.ReadAsStreamAsync(ct); using var output = new MemoryStream();
        var buffer = new byte[16384]; int read;
        while ((read = await source.ReadAsync(buffer, ct)) > 0) { if (output.Length + read > limit) throw new UserError("Сервер обновлений вернул слишком большой ответ."); output.Write(buffer, 0, read); }
        return output.ToArray();
    }
}
