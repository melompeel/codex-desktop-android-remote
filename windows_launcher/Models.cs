using System.Text.Json.Serialization;

namespace CodexRemoteManager;

public sealed record LocalBridgeStatus(
    [property: JsonPropertyName("bridge")] BridgeRuntimeInfo Bridge,
    [property: JsonPropertyName("ipc")] string Ipc,
    [property: JsonPropertyName("pairing")] PairingInfo Pairing,
    [property: JsonPropertyName("devices")] IReadOnlyList<DeviceInfo> Devices);

public sealed record BridgeRuntimeInfo(
    [property: JsonPropertyName("host")] string Host,
    [property: JsonPropertyName("port")] int Port,
    [property: JsonPropertyName("pid")] int Pid,
    [property: JsonPropertyName("startedAt")] long StartedAt,
    [property: JsonPropertyName("desktopVersion")] string? DesktopVersion,
    [property: JsonPropertyName("runtimeVersion")] string? RuntimeVersion,
    [property: JsonPropertyName("addresses")] IReadOnlyList<BridgeAddress>? Addresses);

public sealed record BridgeAddress(
    [property: JsonPropertyName("address")] string Address,
    [property: JsonPropertyName("kind")] string Kind,
    [property: JsonPropertyName("url")] string Url)
{
    public string KindLabel => Kind == "tailscale" ? "Tailscale" : "局域网";
}

public sealed record PairingInfo(
    [property: JsonPropertyName("code")] string Code,
    [property: JsonPropertyName("expiresAt")] long ExpiresAt);

public sealed record DeviceInfo(
    [property: JsonPropertyName("deviceId")] string DeviceId,
    [property: JsonPropertyName("name")] string Name,
    [property: JsonPropertyName("kind")] string Kind,
    [property: JsonPropertyName("createdAt")] long CreatedAt)
{
    public string KindLabel => Kind == "android" ? "Android" : Kind;
    public string AuthorizedAtLabel => DateTimeOffset.FromUnixTimeMilliseconds(CreatedAt)
        .ToLocalTime().ToString("yyyy-MM-dd HH:mm");
}

public sealed record LauncherSettings(int Port = 8766, bool CloseToTray = true, bool StartBridgeOnLaunch = true);
