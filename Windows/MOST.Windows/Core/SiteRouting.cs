using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Net;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace Most.Windows.Core;

public sealed class SiteRouting
{
    public bool RuDirect { get; set; }
    public bool RfDirect { get; set; }
    public bool SuDirect { get; set; }
    public List<string> DirectDomains { get; set; } = new();
    public List<string> ProxyDomains { get; set; } = new();
    public bool Active => RuDirect || RfDirect || SuDirect || DirectDomains.Count > 0 || ProxyDomains.Count > 0;
    public IEnumerable<string> DirectRules => DirectDomains.Select(d => "domain:" + d).Concat(
        (RuDirect ? new[] { "domain:ru" } : Array.Empty<string>()).Concat(
        RfDirect ? new[] { "domain:xn--p1ai" } : Array.Empty<string>()).Concat(
        SuDirect ? new[] { "domain:su" } : Array.Empty<string>()));
    public static List<string> Parse(string text)
    {
        if (text.Length > 65536) throw new UserError("Слишком длинный список сайтов.");
        var values = text.Split(new[] { '\r', '\n' }, StringSplitOptions.RemoveEmptyEntries).Where(s => s.Trim().Length > 0).Select(Normalize).Distinct().ToList();
        if (values.Count > 256) throw new UserError("В каждом списке можно сохранить до 256 сайтов.");
        return values;
    }
    public static string Normalize(string input)
    {
        var value = input.Trim();
        if (value.StartsWith("*.")) value = value[2..];
        if (value.Any(char.IsWhiteSpace)) throw new UserError("Укажите домен или HTTP/HTTPS-адрес сайта, по одному на строку.");
        if (!Uri.TryCreate(value.Contains("://") ? value : "https://" + value, UriKind.Absolute, out var uri) ||
            uri.Scheme is not ("http" or "https") || uri.UserInfo.Length > 0) throw new UserError("Укажите домен или HTTP/HTTPS-адрес сайта.");
        string domain;
        try { domain = new IdnMapping().GetAscii(uri.Host.TrimEnd('.')).ToLowerInvariant(); }
        catch (ArgumentException) { throw new UserError("В списке есть некорректный домен."); }
        if (domain.Length > 253 || !domain.Contains('.') || IPAddress.TryParse(domain, out _) ||
            domain.Split('.').Any(label => label.Length is < 1 or > 63 || !Regex.IsMatch(label, "^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$")))
            throw new UserError("Нужен домен сайта, например example.ru. IP-адреса и правила Xray здесь не принимаются.");
        return domain;
    }
    private static JsonArray Strings(IEnumerable<string> items) => new(items.Select(x => (JsonNode?)JsonValue.Create(x)).ToArray());
    public static void Apply(JsonObject config, SiteRouting policy)
    {
        if (!policy.Active) return;
        var traffic = config["inbounds"]!.AsArray().OfType<JsonObject>().Where(i => i["tag"]!.GetValue<string>() != "diagnostic").ToArray();
        var tags = traffic.Select(i => i["tag"]!.GetValue<string>()).ToArray();
        foreach (var inbound in traffic) inbound["sniffing"] = new JsonObject {
            ["enabled"] = true, ["destOverride"] = Strings(new[] { "http", "tls", "quic" }), ["routeOnly"] = true };
        var rules = config["routing"]!["rules"]!.AsArray();
        // Keep diagnostic traffic and whole-process exclusions ahead of site rules.
        var index = rules.TakeWhile(r => r?["inboundTag"]?.AsArray().Any(t => t?.GetValue<string>() == "diagnostic") == true || r?["process"] != null).Count();
        JsonObject Rule(IEnumerable<string> domains, string outbound) => new() { ["type"] = "field", ["inboundTag"] = Strings(tags), ["domain"] = Strings(domains), ["outboundTag"] = outbound };
        rules.Insert(index++, new JsonObject { ["type"] = "field", ["inboundTag"] = Strings(tags), ["port"] = "53", ["outboundTag"] = "most-sites-dns-out" });
        rules.Insert(index++, new JsonObject { ["type"] = "field", ["inboundTag"] = Strings(new[] { "most-sites-dns" }), ["outboundTag"] = "proxy" });
        if (policy.ProxyDomains.Count > 0) rules.Insert(index++, Rule(policy.ProxyDomains.Select(d => "domain:" + d), "proxy"));
        var direct = policy.DirectRules.ToArray();
        if (direct.Length > 0) rules.Insert(index, Rule(direct, "direct"));
        var servers = new JsonArray();
        var proxy = config["outbounds"]![0]!.AsObject();
        var endpoint = proxy["settings"]!["address"]!.GetValue<string>();
        if (!IPAddress.TryParse(endpoint, out _)) {
            // The VPN server itself must resolve before its tunnel exists.
            foreach (var address in new[] { "1.1.1.1", "8.8.8.8" }) servers.Add(new JsonObject {
                ["address"] = address, ["tag"] = "most-sites-bootstrap", ["domains"] = Strings(new[] { "full:" + endpoint }), ["skipFallback"] = true });
            var stream = proxy["streamSettings"]!.AsObject();
            var sockopt = stream["sockopt"] as JsonObject;
            if (sockopt == null) { sockopt = new JsonObject(); stream["sockopt"] = sockopt; }
            sockopt["domainStrategy"] = "UseIP";
            rules.Insert(index, new JsonObject { ["type"] = "field", ["inboundTag"] = Strings(new[] { "most-sites-bootstrap" }), ["outboundTag"] = "direct" });
        }
        servers.Add("1.1.1.1"); servers.Add("8.8.8.8");
        config["dns"] = new JsonObject { ["tag"] = "most-sites-dns", ["servers"] = servers };
        // DNS outbound handles A/AAAA through the built-in resolver; other query types get an empty response.
        // Browser-owned DoH is opaque and intentionally not intercepted.
        config["outbounds"]!.AsArray().Add(new JsonObject { ["tag"] = "most-sites-dns-out", ["protocol"] = "dns" });
    }
}
