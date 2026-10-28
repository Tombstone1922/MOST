using System;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace Most.Windows.Core;

public sealed record SubscriptionResult(string Content, string Usage);
public static class SubscriptionClient
{
    public static async Task<SubscriptionResult> FetchAsync(string address, bool allowHttp, string deviceId, CancellationToken ct)
    {
        if (!Uri.TryCreate(address.Trim(), UriKind.Absolute, out var current) || current.Scheme is not ("http" or "https") || current.UserInfo.Length > 0)
            throw new UserError("Нужна обычная HTTP/HTTPS-ссылка подписки без логина в адресе.");
        using var handler = new HttpClientHandler { AllowAutoRedirect = false, UseProxy = false, AutomaticDecompression = DecompressionMethods.All };
        using var client = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(25) };
        for (var redirects = 0; redirects <= 5; redirects++) {
            if (current.Scheme == "http" && !allowHttp) throw new UserError("Подписка использует HTTP. Подтвердите разрешение HTTP в окне добавления.");
            using var request = new HttpRequestMessage(HttpMethod.Get, current);
            request.Headers.TryAddWithoutValidation("User-Agent", "MOST-Windows/0.2.0");
            request.Headers.TryAddWithoutValidation("x-hwid", deviceId);
            request.Headers.TryAddWithoutValidation("x-device-os", "Windows");
            request.Headers.TryAddWithoutValidation("x-ver-os", Environment.OSVersion.Version.ToString());
            request.Headers.TryAddWithoutValidation("x-device-model", "MOST Windows x64");
            using var response = await client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, ct);
            bool Header(string name) => response.Headers.TryGetValues(name, out var values) &&
                System.Linq.Enumerable.Any(values, v => v.Equals("true", StringComparison.OrdinalIgnoreCase) || v == "1");
            if (Header("x-hwid-max-devices-reached") || (!response.IsSuccessStatusCode && Header("x-hwid-limit")))
                throw new UserError("Сервер отклонил новое устройство: достигнут лимит. Разрешите Windows-устройство в панели подписки.");
            if (Header("x-hwid-not-supported")) throw new UserError("Сервер требует регистрацию устройства. Проверьте настройки HWID на панели.");
            if ((int)response.StatusCode is >= 300 and < 400 && response.Headers.Location != null) {
                var next = new Uri(current, response.Headers.Location);
                if (next.Scheme is not ("http" or "https") || next.UserInfo.Length > 0) throw new UserError("Неподдерживаемое перенаправление подписки.");
                if (current.Scheme == "https" && next.Scheme == "http" && !allowHttp) throw new UserError("Сервер перенаправляет подписку на HTTP. Нужно отдельное разрешение HTTP.");
                current = next; continue;
            }
            if (!response.IsSuccessStatusCode) throw new UserError(response.StatusCode switch {
                HttpStatusCode.Unauthorized or HttpStatusCode.Forbidden => "Сервер отказал в доступе. Проверьте подписку, срок действия и лимит устройств.",
                HttpStatusCode.NotFound or HttpStatusCode.Gone => "Подписка не найдена или отозвана.",
                HttpStatusCode.TooManyRequests => "Слишком частые запросы. Попробуйте обновить позже.",
                _ => $"Сервис подписок вернул HTTP {(int)response.StatusCode}." });
            await using var input = await response.Content.ReadAsStreamAsync(ct);
            using var bytes = new MemoryStream(); var buffer = new byte[8192];
            while (true) {
                var count = await input.ReadAsync(buffer, ct); if (count == 0) break;
                if (bytes.Length + count > 2_097_152) throw new UserError("Ответ подписки больше 2 МБ.");
                await bytes.WriteAsync(buffer.AsMemory(0, count), ct);
            }
            var usage = response.Headers.TryGetValues("subscription-userinfo", out var info) ? string.Join("; ", info) : "";
            return new SubscriptionResult(Encoding.UTF8.GetString(bytes.ToArray()), usage);
        }
        throw new UserError("Слишком много перенаправлений подписки.");
    }
}
