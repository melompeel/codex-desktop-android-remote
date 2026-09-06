using System.Net;
using System.Net.Http;
using System.Net.Http.Json;
using System.Text.Json;

namespace CodexRemoteManager;

public sealed class LocalBridgeClient : IDisposable
{
    private readonly HttpClient _http = new() { Timeout = TimeSpan.FromSeconds(2) };
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);

    public async Task<LocalBridgeStatus?> GetStatusAsync(int port, CancellationToken cancellationToken = default)
    {
        try
        {
            return await _http.GetFromJsonAsync<LocalBridgeStatus>(
                $"http://127.0.0.1:{port}/v1/local/status", JsonOptions, cancellationToken);
        }
        catch (Exception error) when (error is HttpRequestException or TaskCanceledException or JsonException)
        {
            return null;
        }
    }

    public async Task<PairingInfo> RotatePairingAsync(int port, CancellationToken cancellationToken = default)
    {
        using var response = await _http.PostAsync(
            $"http://127.0.0.1:{port}/v1/local/pairing/rotate", null, cancellationToken);
        response.EnsureSuccessStatusCode();
        var payload = await response.Content.ReadFromJsonAsync<RotatePairingResponse>(JsonOptions, cancellationToken);
        return payload?.Pairing ?? throw new InvalidOperationException("Bridge 没有返回新的配对码。");
    }

    public async Task RequestShutdownAsync(int port, CancellationToken cancellationToken = default)
    {
        try
        {
            using var response = await _http.PostAsync(
                $"http://127.0.0.1:{port}/v1/local/shutdown", null, cancellationToken);
            if (response.StatusCode is not HttpStatusCode.Accepted and not HttpStatusCode.OK)
                response.EnsureSuccessStatusCode();
        }
        catch (HttpRequestException)
        {
            // Closing the listener may race with the HTTP response.
        }
    }

    public async Task RevokeDeviceAsync(int port, string deviceId,
        CancellationToken cancellationToken = default)
    {
        using var response = await _http.DeleteAsync(
            $"http://127.0.0.1:{port}/v1/local/devices/{Uri.EscapeDataString(deviceId)}",
            cancellationToken);
        response.EnsureSuccessStatusCode();
    }

    public void Dispose() => _http.Dispose();

    private sealed record RotatePairingResponse(
        [property: System.Text.Json.Serialization.JsonPropertyName("pairing")] PairingInfo Pairing);
}
