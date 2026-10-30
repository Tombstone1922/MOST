using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json.Nodes;

namespace Most.Windows.Core;

public static class ConfigBuilder
{
    public const string InterfaceName = "MOST";
    private static JsonArray Strings(IEnumerable<string> values) => new(values.Select(x => (JsonNode?)JsonValue.Create(x)).ToArray());
    public static JsonObject Build(Profile profile, AppState state, int httpPort, int socksPort, bool ipv6)
    {
        var proxy = new JsonObject { ["tag"] = "proxy", ["protocol"] = "vless",
            ["settings"] = new JsonObject { ["address"] = profile.Host, ["port"] = profile.Port, ["id"] = profile.Uuid,
                ["encryption"] = profile.Encryption, ["flow"] = profile.Flow }, ["streamSettings"] = profile.Stream.DeepClone() };
        var inbounds = new JsonArray {
            new JsonObject { ["tag"] = "diagnostic", ["listen"] = "127.0.0.1", ["port"] = httpPort, ["protocol"] = "http", ["settings"] = new JsonObject() },
            new JsonObject { ["tag"] = "local-socks", ["listen"] = "127.0.0.1", ["port"] = socksPort, ["protocol"] = "socks", ["settings"] = new JsonObject { ["udp"] = true } } };
        if (state.TunMode) inbounds.Add(new JsonObject { ["tag"] = "most-tun", ["protocol"] = "tun",
            ["settings"] = new JsonObject { ["name"] = InterfaceName, ["desc"] = "MOST", ["mtu"] = 1500,
                ["gateway"] = Strings(ipv6 ? new[] { "172.29.217.1/30", "fd4d:4f53:5400::1/126" } : new[] { "172.29.217.1/30" }),
                ["dns"] = Strings(new[] { "1.1.1.1", "8.8.8.8" }),
                ["autoSystemRoutingTable"] = Strings(ipv6 ? new[] { "0.0.0.0/0", "::/0" } : new[] { "0.0.0.0/0" }),
                ["autoOutboundsInterface"] = "auto", ["autoSystemWfpBlockLeak"] = Strings(new[] { "dns", "misconfigtun" }) } });
        var rules = new JsonArray { new JsonObject { ["type"] = "field", ["inboundTag"] = Strings(new[] { "diagnostic" }), ["outboundTag"] = "proxy" } };
        if (state.TunMode && state.ExcludedApps.Count > 0) rules.Add(new JsonObject { ["type"] = "field", ["inboundTag"] = Strings(new[] { "most-tun" }),
            ["process"] = Strings(state.ExcludedApps), ["outboundTag"] = "direct" });
        rules.Add(new JsonObject { ["type"] = "field", ["network"] = "tcp,udp", ["outboundTag"] = "proxy" });
        var config = new JsonObject { ["log"] = new JsonObject { ["loglevel"] = "warning", ["access"] = "none" },
            ["inbounds"] = inbounds, ["outbounds"] = new JsonArray { proxy,
                new JsonObject { ["tag"] = "direct", ["protocol"] = "freedom" }, new JsonObject { ["tag"] = "block", ["protocol"] = "blackhole" } },
            ["routing"] = new JsonObject { ["domainStrategy"] = "AsIs", ["rules"] = rules } };
        SiteRouting.Apply(config, state.SiteRouting);
        return config;
    }
}
