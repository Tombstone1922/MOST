using System;
using System.IO;
using System.Linq;
using System.Text.Json.Nodes;
using Most.Windows.Core;

var count = 0;
void Check(bool condition, string message) { if (!condition) throw new Exception(message); count++; }
void Reject(Action action, string message) { try { action(); } catch (UserError) { count++; return; } throw new Exception(message); }
const string id = "11111111-1111-4111-8111-111111111111";
var key = Convert.ToBase64String(Enumerable.Repeat((byte)1, 32).ToArray()).TrimEnd('=').Replace('+', '-').Replace('/', '_');
string Link(string type, string label = "MSK%20%E2%86%92%20DE") => $"vless://{id}@entry.msk1.example.com:443?encryption=none&security=reality&pbk={key}&sni=example.com&sid=abcd&fp=firefox&type={type}&serviceName=testgrpc&path=%2Ftest&mode=auto#{label}";
var profiles = ProfileParser.Parse(Link("tcp") + "\n" + Link("grpc") + "\n" + Link("xhttp"), "family");
Check(profiles.Count == 3, "three transports");
Check(profiles.All(p => p.IsBridge && p.Country == "de"), "bridge label beats Moscow entry");
Check(RoutePolicy.Country("[МСК-прямое] BorR--de-grpc", "entry.msk1.example.com") == "de", "existing generated client names");
Check(RoutePolicy.IsBridge("[МСК-прямое] BorR--de-grpc", "entry.msk1.example.com"), "Moscow entry with foreign target is a bridge");
Check(!RoutePolicy.IsBridge("Германия", "node.de13.example.com"), "foreign direct endpoint");
Check(RoutePolicy.ExitMatches(profiles[0], "DE") && !RoutePolicy.ExitMatches(profiles[0], "RU"), "actual country comparison");
Check(ProfileParser.Parse(Link("tcp") + "\n" + Link("tcp"), "family").Count == 1, "duplicate links");
Check(ProfileParser.Parse(Convert.ToBase64String(System.Text.Encoding.UTF8.GetBytes(Link("tcp"))), "family").Count == 1, "base64 subscription");
Check(ProfileParser.Parse(Link("tcp"), "different")[0].Id != profiles[0].Id, "subscription identity isolation");
Reject(() => ProfileParser.Parse("vless://bad@host:443?type=tcp", "x"), "invalid UUID rejected");
Reject(() => ProfileParser.Parse(Link("ws"), "x"), "unsupported transport rejected");
Reject(() => ProfileParser.Parse(Link("tcp").Replace("security=reality", "security=tls&allowInsecure=1"), "x"), "insecure TLS rejected");
var state = new AppState { Profiles = profiles, SelectedId = profiles[0].Id, ExcludedApps = { @"C:\Apps\game.exe" } };
Check(RoutePolicy.Filter(state).Count == 3, "bridges only by default");
state.DirectRoutes = true; Check(RoutePolicy.Filter(state).Count == 0, "direct filtering"); state.DirectRoutes = false;
var otherGroup = ProfileParser.Parse(Link("tcp"), "other")[0];
var directProfile = ProfileParser.Parse(Link("grpc", "Germany" ).Replace("entry.msk1.example.com", "node.de13.example.com"), "family")[0];
var nlProfile = ProfileParser.Parse(Link("tcp", "bridge-nl-tcp"), "family")[0];
var candidateState = Storage.Clone(state); candidateState.Profiles.AddRange(new[] { otherGroup, directProfile, nlProfile });
candidateState.LastWorking[RoutePolicy.CacheKey(profiles[2])] = profiles[2].Id;
var candidates = RoutePolicy.Candidates(candidateState, "family", "de", false);
Check(candidates.Count == 3 && candidates.All(p => p.GroupId == "family" && p.Country == "de" && p.IsBridge), "automatic candidates never cross subscription, country or route type");
Check(candidates[0].Id == profiles[2].Id, "remembered working connection first");
Check(RoutePolicy.Candidates(candidateState, "family", "de", false, profiles[2].Id)[^1].Id == profiles[2].Id, "failed working connection goes last on recovery");
Check(RoutePolicy.Candidates(candidateState, "", "de", false).Count == 0, "no automatic all-subscription selection");
Check(RoutePolicy.Candidates(candidateState, "family", "", false).Count == 0, "no automatic unknown-country selection");
Check(RoutePolicy.Candidates(candidateState, "family", "de", true).Single().Id == directProfile.Id, "direct selection is isolated from bridges");
var previousState = System.Text.Json.JsonSerializer.Deserialize<AppState>("{\"Schema\":1,\"DeviceId\":\"existing-hwid\",\"SelectedId\":\"existing-server\",\"TunMode\":false,\"ExcludedApps\":[\"C:\\\\Apps\\\\game.exe\"]}", Storage.Json)!;
Check(previousState.DeviceId == "existing-hwid" && previousState.SelectedId == "existing-server" && !previousState.TunMode && previousState.ExcludedApps.Count == 1, "0.1.0 settings remain readable");
Check(!previousState.AutoCountry && previousState.Favorites.Count == 0 && previousState.CheckUpdates, "new settings have compatible defaults");
JsonObject Release(string version, string prefix = "windows-v", string host = "github.com", string digest = "") => new() {
    ["draft"] = false, ["tag_name"] = prefix + version, ["body"] = "Понятные пользователю улучшения.",
    ["assets"] = new JsonArray(new JsonObject { ["name"] = $"MOST-Windows-{version}-Setup.exe", ["size"] = 60000000,
        ["browser_download_url"] = $"https://{host}/Tombstone1922/MOST/releases/download/{prefix}{version}/MOST-Windows-{version}-Setup.exe",
        ["digest"] = digest.Length > 0 ? digest : "sha256:" + new string('a', 64) }) };
var releases = new JsonArray(Release("0.1.0"), Release("0.3.0"), Release("0.4.5", "v"), Release("0.2.0"));
Check(UpdateManager.SelectRelease(releases.ToJsonString(), "0.2.0")?.Version == "0.3.0", "OTA selects newest Windows update, independent from Android and list order");
Check(UpdateManager.SelectRelease(new JsonArray(Release("0.2.0")).ToJsonString(), "0.2.0") == null, "OTA cannot downgrade or reinstall same version");
Check(UpdateManager.SelectRelease(new JsonArray(Release("0.3.0", host: "example.com")).ToJsonString(), "0.2.0") == null, "OTA refuses foreign installer URL");
Check(UpdateManager.SelectRelease(new JsonArray(Release("0.3.0", digest: "sha256:bad")).ToJsonString(), "0.2.0") == null, "OTA refuses missing or invalid digest");
var draftRelease = Release("0.3.0"); draftRelease["draft"] = true;
Check(UpdateManager.SelectRelease(new JsonArray(draftRelease).ToJsonString(), "0.2.0") == null, "draft installers cannot reach users");
var output = Path.GetFullPath("out/windows/config-tests"); Directory.CreateDirectory(output);
foreach (var profile in profiles) {
    var config = ConfigBuilder.Build(profile, state, 16810, 16811, true);
    File.WriteAllText(Path.Combine(output, profile.Transport + ".json"), config.ToJsonString());
    Check(config["routing"]!["rules"]![0]!["outboundTag"]!.GetValue<string>() == "proxy", "diagnostic never bypasses VPN");
    Check(config["inbounds"]!.AsArray().Any(n => n?["protocol"]?.GetValue<string>() == "tun"), "real TUN generated");
    Check(config["routing"]!["rules"]!.AsArray().Any(n => n?["process"] != null && n?["inboundTag"]![0]?.GetValue<string>() == "most-tun"), "process exclusions limited to TUN");
}
Check(SiteRouting.Parse("HTTPS://Example.RU/account\n*.example.ru\nпример.рф").SequenceEqual(new[] { "example.ru", "xn--e1afmkfd.xn--p1ai" }), "site domains normalize URLs, IDN and duplicates");
foreach (var invalid in new[] { "127.0.0.1", "localhost", "domain:ru", "regexp:.*", "a..ru", "https://user:pass@example.ru", "https://[::1]", "ftp://example.ru" })
    Reject(() => SiteRouting.Parse(invalid), "invalid site rule rejected");
Check(SiteRouting.Parse("\n  \n").Count == 0, "empty site list accepted");
Check(!previousState.SiteRouting.Active, "0.2.0 settings keep site rules disabled");
var siteState = Storage.Clone(state);
siteState.SiteRouting = new SiteRouting { RuDirect = true, RfDirect = true, SuDirect = true, DirectDomains = { "bank.example" }, ProxyDomains = { "keep.ru" } };
var siteConfig = ConfigBuilder.Build(profiles[0], siteState, 16810, 16811, true);
File.WriteAllText(Path.Combine(output, "sites.json"), siteConfig.ToJsonString());
var siteRules = siteConfig["routing"]!["rules"]!.AsArray();
string SiteRoute(string host) => siteRules.FirstOrDefault(rule => rule?["domain"]?.AsArray().Any(d => {
    var suffix = d!.GetValue<string>()[7..]; return host == suffix || host.EndsWith("." + suffix, StringComparison.OrdinalIgnoreCase);
}) == true)?["outboundTag"]?.GetValue<string>() ?? "proxy";
Check(SiteRoute("example.ru") == "direct" && SiteRoute("sub.example.ru") == "direct", "ru sites and subdomains use direct route");
Check(SiteRoute("keep.ru") == "proxy" && SiteRoute("login.keep.ru") == "proxy", "forced VPN overrides ru direct");
Check(SiteRoute("example.ru.evil.com") == "proxy" && SiteRoute("notru.com") == "proxy", "suffix matching has label boundaries");
Check(SiteRoute("bank.example") == "direct" && SiteRoute("пример.xn--p1ai") == "direct" && SiteRoute("example.su") == "direct", "additional sites and optional zones");
Check(siteRules[0]!["outboundTag"]!.GetValue<string>() == "proxy" && siteRules[1]?["process"] != null, "diagnostics and whole-app exclusions retain priority");
Check(siteConfig["inbounds"]![0]?["sniffing"] == null && siteConfig["inbounds"]![1]!["sniffing"]!["routeOnly"]!.GetValue<bool>(), "domain detection excludes diagnostic inbound");
Check(siteRules.Any(r => r?["inboundTag"]?.AsArray().Any(t => t?.GetValue<string>() == "most-sites-bootstrap") == true && r?["outboundTag"]?.GetValue<string>() == "direct"), "VPN endpoint DNS bootstraps outside its own tunnel");
var sourceConfig = ConfigBuilder.Build(profiles[1], state, 16810, 16811, false);
sourceConfig["routing"] = new JsonObject { ["script"] = "must-not-run.lua" };
sourceConfig["outbounds"]![0]!["streamSettings"]!["sockopt"] = new JsonObject { ["dialerProxy"] = "untrusted" };
var fromJson = ProfileParser.Parse(sourceConfig.ToJsonString(), "json");
Check(fromJson.Count == 1 && !fromJson[0].Stream.ContainsKey("sockopt"), "remote routing/scripts/dialer discarded");
state.TunMode = false;
Check(ConfigBuilder.Build(profiles[0], state, 16810, 16811, false)["inbounds"]!.AsArray().Count == 2, "proxy mode has no system routes");
var storePath = Path.Combine(Path.GetTempPath(), "most-tests-" + Guid.NewGuid().ToString("N") + ".bin");
try {
    var store = new Storage(storePath); var initial = store.Load();
    Check(store.Load().DeviceId == initial.DeviceId, "HWID persists before the first subscription request or rejection");
    store.Save(state); var restored = store.Load();
    Check(restored.DeviceId == state.DeviceId && restored.Profiles.Count == 3, "DPAPI state roundtrip and stable HWID");
    Check(!System.Text.Encoding.UTF8.GetString(File.ReadAllBytes(storePath)).Contains(id), "credentials not plaintext on disk");
} finally { File.Delete(storePath); }
Console.WriteLine($"MOST Windows: {count} focused assertions passed.");
