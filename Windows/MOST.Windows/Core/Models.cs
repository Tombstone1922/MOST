using System;
using System.Collections.Generic;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace Most.Windows.Core;

public sealed class Profile
{
    public string Id { get; set; } = "";
    public string GroupId { get; set; } = "";
    public string Name { get; set; } = "";
    public string Host { get; set; } = "";
    public int Port { get; set; }
    public string Uuid { get; set; } = "";
    public string Flow { get; set; } = "";
    public string Encryption { get; set; } = "none";
    public JsonObject Stream { get; set; } = new();
    public string Transport => Stream["network"]?.GetValue<string>() ?? "tcp";
    public string Country => RoutePolicy.Country(Name, Host);
    public bool IsBridge => RoutePolicy.IsBridge(Name, Host);
    public string RouteLabel => IsBridge ? "Через Москву" : "Без моста";
    public string FriendlyName => $"{RoutePolicy.CountryName(Country)} · {(IsBridge ? "Через Москву" : "Без моста")}";
    public string Display => $"{Name}\n{RouteLabel} · {Transport.ToUpperInvariant()} · {Host}";
    public void AssignId() => Id = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(
        string.Join("|", GroupId, Host.ToLowerInvariant(), Port.ToString(System.Globalization.CultureInfo.InvariantCulture), Uuid, Flow, Encryption, Stream.ToJsonString())))).ToLowerInvariant();
}

public sealed class Subscription
{
    public string Id { get; set; } = Guid.NewGuid().ToString("N");
    public string Name { get; set; } = "";
    public string Url { get; set; } = "";
    public bool AllowHttp { get; set; }
    public DateTimeOffset? Updated { get; set; }
    public string Usage { get; set; } = "";
}

public sealed class AppState
{
    public int Schema { get; set; } = 1;
    public string DeviceId { get; set; } = Guid.NewGuid().ToString("N");
    public List<Profile> Profiles { get; set; } = new();
    public List<Subscription> Subscriptions { get; set; } = new();
    public string SelectedId { get; set; } = "";
    public string GroupFilter { get; set; } = "";
    public string CountryFilter { get; set; } = "";
    public bool DirectRoutes { get; set; }
    public SiteRouting SiteRouting { get; set; } = new();
    public long ConnectionSettingsRevision { get; set; }
    public bool TunMode { get; set; } = true;
    public List<string> ExcludedApps { get; set; } = new();
    public bool AutoCountry { get; set; }
    public string AutoGroupId { get; set; } = "";
    public string AutoCountryCode { get; set; } = "";
    public bool AutoDirect { get; set; }
    public List<string> Favorites { get; set; } = new();
    public bool CheckUpdates { get; set; } = true;
    public string NotifiedUpdate { get; set; } = "";
    public Dictionary<string, string> LastWorking { get; set; } = new();
    public Profile? Selected => Profiles.FirstOrDefault(p => p.Id == SelectedId);
}

public static class RoutePolicy
{
    private static readonly Regex Bridge = new(@"(?:bridge[-_ ]|(?:msk|мск|москва)\s*(?:→|->|[-_])\s*)(nl|de|pl|fi)(?:[^a-z]|$)", RegexOptions.IgnoreCase);
    private static readonly (string Code, string Pattern)[] Names = {
        ("nl", @"🇳🇱|нидерланд|голланд|netherlands|\bnl\b"),
        ("de", @"🇩🇪|германи|germany|\bde\b"), ("pl", @"🇵🇱|польш|poland|\bpl\b"),
        ("fi", @"🇫🇮|финлянд|finland|\bfi\b"), ("msk", @"🇷🇺|москва|россия|russia|\bmsk\b") };
    public static string Country(string name, string host)
    {
        var bridge = Bridge.Match(name);
        if (bridge.Success) return bridge.Groups[1].Value.ToLowerInvariant();
        foreach (var (code, pattern) in Names)
            if (Regex.IsMatch(name, pattern, RegexOptions.IgnoreCase)) return code;
        foreach (var (code, _) in Names)
            if (Regex.IsMatch(host, $@"(?:^|\.){code}\d*(?:\.|$)", RegexOptions.IgnoreCase)) return code;
        return "";
    }
    public static bool IsBridge(string name, string host) => Bridge.IsMatch(name) ||
        (Regex.IsMatch(host, @"(?:^|\.)msk\d*(?:\.|$)", RegexOptions.IgnoreCase) && Country(name, host) is "nl" or "de" or "pl" or "fi");
    public static string CountryName(string code) => code switch {
        "nl" => "Нидерланды", "de" => "Германия", "pl" => "Польша", "fi" => "Финляндия", "msk" => "Россия", _ => "Другая страна" };
    public static bool ExitMatches(Profile profile, string measured) => string.IsNullOrEmpty(profile.Country) ||
        string.IsNullOrEmpty(measured) || measured.Equals(profile.Country == "msk" ? "RU" : profile.Country, StringComparison.OrdinalIgnoreCase);
    public static IReadOnlyList<Profile> Filter(AppState state, string search = "") => state.Profiles.Where(p =>
        (state.GroupFilter.Length == 0 || p.GroupId == state.GroupFilter) &&
        (state.CountryFilter.Length == 0 || p.Country == state.CountryFilter) &&
        p.IsBridge != state.DirectRoutes && (search.Length == 0 || p.Name.Contains(search, StringComparison.OrdinalIgnoreCase)))
        .OrderBy(p => p.Country).ThenBy(p => p.Name).ToArray();
    public static IReadOnlyList<Profile> Candidates(AppState state, string group, string country, bool direct, string failedId = "")
    {
        // Automatic selection is always restricted to one subscription, exit country and route type.
        if (group.Length == 0 || country.Length == 0) return Array.Empty<Profile>();
        var items = state.Profiles.Where(p => p.GroupId == group && p.Country == country && p.IsBridge != direct).ToArray();
        var key = $"{group}:{country}:{!direct}";
        state.LastWorking.TryGetValue(key, out var working);
        return items.OrderBy(p => p.Id == failedId ? 1 : 0)
            .ThenBy(p => p.Id == working ? 0 : p.Id == state.SelectedId ? 1 : 2)
            .ThenBy(p => p.Transport == "tcp" ? 0 : p.Transport == "grpc" ? 1 : 2).Take(12).ToArray();
    }
    public static string CacheKey(Profile p) => $"{p.GroupId}:{p.Country}:{p.IsBridge}";
}
