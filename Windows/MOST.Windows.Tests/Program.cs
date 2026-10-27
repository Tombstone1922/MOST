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
var output = Path.GetFullPath("out/windows/config-tests"); Directory.CreateDirectory(output);
foreach (var profile in profiles) {
    var config = ConfigBuilder.Build(profile, state, 16810, 16811, true);
    File.WriteAllText(Path.Combine(output, profile.Transport + ".json"), config.ToJsonString());
    Check(config["routing"]!["rules"]![0]!["outboundTag"]!.GetValue<string>() == "proxy", "diagnostic never bypasses VPN");
    Check(config["inbounds"]!.AsArray().Any(n => n?["protocol"]?.GetValue<string>() == "tun"), "real TUN generated");
    Check(config["routing"]!["rules"]!.AsArray().Any(n => n?["process"] != null && n?["inboundTag"]![0]?.GetValue<string>() == "most-tun"), "process exclusions limited to TUN");
}
var sourceConfig = ConfigBuilder.Build(profiles[1], state, 16810, 16811, false);
sourceConfig["routing"] = new JsonObject { ["script"] = "must-not-run.lua" };
sourceConfig["outbounds"]![0]!["streamSettings"]!["sockopt"] = new JsonObject { ["dialerProxy"] = "untrusted" };
var fromJson = ProfileParser.Parse(sourceConfig.ToJsonString(), "json");
Check(fromJson.Count == 1 && !fromJson[0].Stream.ContainsKey("sockopt"), "remote routing/scripts/dialer discarded");
state.TunMode = false;
Check(ConfigBuilder.Build(profiles[0], state, 16810, 16811, false)["inbounds"]!.AsArray().Count == 2, "proxy mode has no system routes");
var storePath = Path.Combine(Path.GetTempPath(), "most-tests-" + Guid.NewGuid().ToString("N") + ".bin");
try {
    var store = new Storage(storePath); store.Save(state); var restored = store.Load();
    Check(restored.DeviceId == state.DeviceId && restored.Profiles.Count == 3, "DPAPI state roundtrip and stable HWID");
    Check(!System.Text.Encoding.UTF8.GetString(File.ReadAllBytes(storePath)).Contains(id), "credentials not plaintext on disk");
} finally { File.Delete(storePath); }
Console.WriteLine($"MOST Windows: {count} focused assertions passed.");
