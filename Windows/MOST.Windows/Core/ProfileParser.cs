using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Most.Windows.Core;

/// Only VLESS endpoint/transport data is imported; remote routing, scripts and file paths never execute.
public static class ProfileParser
{
    public static List<Profile> Parse(string text, string groupId)
    {
        if (text.Length > 2_097_152) throw new UserError("Слишком большой список подключений (максимум 2 МБ).");
        text = text.Trim().TrimStart('\uFEFF');
        var result = new List<Profile>();
        if (text.StartsWith('{') || text.StartsWith('[')) {
            using var doc = JsonDocument.Parse(text, new JsonDocumentOptions { MaxDepth = 48 });
            ParseJson(doc.RootElement, result);
        } else {
            if (!text.StartsWith("vless://", StringComparison.OrdinalIgnoreCase)) {
                try { text = Encoding.UTF8.GetString(Convert.FromBase64String(System.Text.RegularExpressions.Regex.Replace(text, @"\s+", "").Replace('-', '+').Replace('_', '/').PadRight((System.Text.RegularExpressions.Regex.Replace(text, @"\s+", "").Length + 3) / 4 * 4, '='))); }
                catch (FormatException) { throw new UserError("Ожидались VLESS-ссылки, Base64-подписка или Xray JSON."); }
            }
            if (text.TrimStart().StartsWith('{') || text.TrimStart().StartsWith('[')) return Parse(text, groupId);
            foreach (var line in text.Split(new[] { '\r', '\n' }, StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)) {
                if (line.StartsWith('#')) continue;
                if (!line.StartsWith("vless://", StringComparison.OrdinalIgnoreCase)) throw new UserError("В подписке есть неподдерживаемый профиль. Первая версия поддерживает VLESS TCP, gRPC и XHTTP.");
                result.Add(ParseUri(line));
            }
        }
        if (result.Count == 0) throw new UserError("Сервер не вернул поддерживаемых VLESS-подключений. Проверьте доступ и лимит устройств.");
        if (result.Count > 2000) throw new UserError("В одной подписке допускается до 2000 подключений.");
        foreach (var p in result) { p.GroupId = groupId; Validate(p); p.AssignId(); }
        return result.DistinctBy(p => p.Id).ToList();
    }
    private static string Decode(string value) => Uri.UnescapeDataString(value.Replace('+', ' '));
    public static Profile ParseUri(string text)
    {
        if (!Uri.TryCreate(text, UriKind.Absolute, out var uri) || uri.Scheme != "vless" || uri.UserInfo.Contains(':')) throw new UserError("Некорректная VLESS-ссылка.");
        var q = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (var part in uri.Query.TrimStart('?').Split('&', StringSplitOptions.RemoveEmptyEntries)) {
            var kv = part.Split('=', 2); q[Decode(kv[0])] = kv.Length > 1 ? Decode(kv[1]) : "";
        }
        string Get(string key, string value = "") => q.TryGetValue(key, out var v) ? v : value;
        var network = Get("type", "tcp");
        var stream = new JsonObject { ["network"] = network, ["security"] = Get("security", "none") };
        if (Get("security") == "reality") stream["realitySettings"] = new JsonObject {
            ["serverName"] = Get("sni", uri.Host), ["publicKey"] = Get("pbk"), ["shortId"] = Get("sid"),
            ["fingerprint"] = Get("fp", "chrome"), ["spiderX"] = Get("spx", "/"), ["mldsa65Verify"] = Get("pqv") };
        if (Get("security") == "tls") {
            if (Get("allowInsecure") is "1" or "true") throw new UserError("Ссылка отключает проверку TLS-сертификата. Нужна конфигурация с корректным сертификатом.");
            stream["tlsSettings"] = new JsonObject { ["serverName"] = Get("sni", uri.Host), ["fingerprint"] = Get("fp", "chrome") };
            if (Get("alpn").Length > 0) stream["tlsSettings"]!["alpn"] = new JsonArray(Get("alpn").Split(',').Select(x => (JsonNode?)JsonValue.Create(x)).ToArray());
        }
        if (network == "grpc") stream["grpcSettings"] = new JsonObject { ["serviceName"] = Get("serviceName"), ["authority"] = Get("authority"), ["multiMode"] = Get("mode") == "multi" };
        if (network is "xhttp" or "splithttp") {
            var settings = new JsonObject { ["path"] = Get("path", "/"), ["host"] = Get("host"), ["mode"] = Get("mode", "auto") };
            if (Get("extra").Length > 0) settings["extra"] = JsonNode.Parse(Get("extra"));
            if (Get("x_padding_bytes").Length > 0) {
                var extra = settings["extra"] as JsonObject ?? new JsonObject();
                extra["xPaddingBytes"] = Get("x_padding_bytes"); if (settings["extra"] == null) settings["extra"] = extra;
            }
            stream["xhttpSettings"] = settings;
        }
        var p = new Profile { Name = Decode(uri.Fragment.TrimStart('#')), Host = uri.IdnHost,
            Port = uri.IsDefaultPort ? 443 : uri.Port, Uuid = Uri.UnescapeDataString(uri.UserInfo),
            Encryption = Get("encryption", "none"), Flow = Get("flow"), Stream = SanitizeStream(stream) };
        if (p.Name.Length == 0) p.Name = uri.Host;
        Validate(p); return p;
    }
    private static void ParseJson(JsonElement node, List<Profile> result)
    {
        if (node.ValueKind == JsonValueKind.Array) { foreach (var item in node.EnumerateArray()) ParseJson(item, result); return; }
        if (node.ValueKind != JsonValueKind.Object) throw new UserError("Неподдерживаемый JSON подписки.");
        var root = JsonNode.Parse(node.GetRawText())!.AsObject();
        var name = root["remarks"]?.GetValue<string>() ?? root["remark"]?.GetValue<string>() ?? "VLESS";
        if (root["outbounds"] is not JsonArray outbounds) throw new UserError("JSON не содержит Xray outbounds.");
        foreach (var outbound in outbounds.OfType<JsonObject>()) {
            if (outbound["protocol"]?.GetValue<string>() != "vless") continue;
            var settings = outbound["settings"] as JsonObject ?? throw new UserError("Нет VLESS settings.");
            JsonObject server = settings, user = settings;
            if (settings["vnext"] is JsonArray next && next.FirstOrDefault() is JsonObject first) {
                server = first;
                user = (first["users"] as JsonArray)?.FirstOrDefault() as JsonObject ?? throw new UserError("Нет VLESS users.");
            }
            result.Add(new Profile { Name = name, Host = server["address"]?.GetValue<string>() ?? "",
                Port = int.Parse(server["port"]?.ToString() ?? "443", CultureInfo.InvariantCulture),
                Uuid = user["id"]?.GetValue<string>() ?? "", Flow = user["flow"]?.GetValue<string>() ?? "",
                Encryption = user["encryption"]?.GetValue<string>() ?? "none",
                Stream = SanitizeStream(outbound["streamSettings"] as JsonObject ?? new JsonObject()) });
        }
    }
    private static JsonObject Pick(JsonObject source, params string[] keys)
    {
        var obj = new JsonObject(); foreach (var key in keys) if (source[key] is JsonNode value) obj[key] = value.DeepClone(); return obj;
    }
    private static JsonObject SanitizeStream(JsonObject source)
    {
        var network = source["network"]?.GetValue<string>() ?? "tcp";
        if (network == "raw") network = "tcp";
        if (network == "splithttp") network = "xhttp";
        if (network is not ("tcp" or "grpc" or "xhttp")) throw new UserError("Поддерживаются только TCP, gRPC и XHTTP.");
        var security = source["security"]?.GetValue<string>() ?? "none";
        if (security is not ("none" or "tls" or "reality")) throw new UserError("Неподдерживаемая защита транспорта.");
        var result = new JsonObject { ["network"] = network, ["security"] = security };
        if (security == "reality") {
            var reality = source["realitySettings"] as JsonObject ?? throw new UserError("Отсутствуют настройки REALITY.");
            result["realitySettings"] = Pick(reality, "serverName", "publicKey", "password", "shortId", "fingerprint", "spiderX", "mldsa65Verify");
        }
        if (security == "tls") {
            var tls = source["tlsSettings"] as JsonObject ?? new JsonObject();
            if (tls["allowInsecure"]?.GetValue<bool>() == true) throw new UserError("JSON отключает проверку TLS-сертификата.");
            result["tlsSettings"] = Pick(tls, "serverName", "fingerprint", "alpn");
        }
        if (network == "grpc") result["grpcSettings"] = Pick(source["grpcSettings"] as JsonObject ?? new(), "serviceName", "authority", "multiMode");
        if (network == "xhttp") {
            var http = source["xhttpSettings"] as JsonObject ?? source["splithttpSettings"] as JsonObject ?? new();
            var safe = Pick(http, "host", "path", "mode");
            if (http["extra"] is JsonObject extra) safe["extra"] = Pick(extra, "xPaddingBytes", "noGRPCHeader", "noSSEHeader", "scMaxEachPostBytes", "scMinPostsIntervalMs", "scMaxBufferedPosts", "xmux", "mode");
            result["xhttpSettings"] = safe;
        }
        return result;
    }
    private static void Validate(Profile p)
    {
        if (!Guid.TryParse(p.Uuid, out _) || p.Port is < 1 or > 65535 || string.IsNullOrWhiteSpace(p.Host) || p.Host.Any(char.IsWhiteSpace)) throw new UserError("Некорректные адрес, порт или UUID VLESS.");
        if (p.Encryption != "none") throw new UserError("Первая версия поддерживает VLESS с encryption=none.");
        if (p.Flow is not ("" or "xtls-rprx-vision")) throw new UserError("Неподдерживаемое значение flow.");
        if (p.Stream["security"]?.GetValue<string>() == "reality") {
            var r = p.Stream["realitySettings"]!;
            if (string.IsNullOrWhiteSpace(r["publicKey"]?.GetValue<string>() ?? r["password"]?.GetValue<string>())) throw new UserError("Для REALITY отсутствует публичный ключ.");
        }
    }
}

public sealed class UserError : Exception
{
    public UserError(string message) : base(message) { }
}
