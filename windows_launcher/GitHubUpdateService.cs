using System.IO;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace CodexRemoteManager;

public sealed class GitHubUpdateService : IDisposable
{
    private const string LatestReleaseUrl =
        "https://api.github.com/repos/melompeel/codex-desktop-remote/releases/latest";
    private readonly HttpClient _http = new() { Timeout = TimeSpan.FromSeconds(20) };
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);

    public GitHubUpdateService()
    {
        _http.DefaultRequestHeaders.UserAgent.Add(
            new ProductInfoHeaderValue("CodexRemoteManager", "1.0"));
        _http.DefaultRequestHeaders.Accept.Add(
            new MediaTypeWithQualityHeaderValue("application/vnd.github+json"));
    }

    public async Task<WindowsUpdateInfo?> CheckAsync(
        Version currentVersion,
        CancellationToken cancellationToken = default)
    {
        var release = await _http.GetFromJsonAsync<GitHubRelease>(
            LatestReleaseUrl, JsonOptions, cancellationToken)
            ?? throw new InvalidOperationException("GitHub 没有返回版本信息。");
        if (release.Draft || release.Prerelease || !IsNewerVersion(currentVersion, release.TagName))
            return null;
        var asset = SelectWindowsAsset(release.Assets)
            ?? throw new InvalidOperationException("新版本暂时没有 Windows x64 安装包。");
        return new WindowsUpdateInfo(
            release.TagName.TrimStart('v', 'V'),
            release.HtmlUrl,
            release.Body?.Trim() ?? string.Empty,
            asset.Name,
            asset.BrowserDownloadUrl,
            asset.Size);
    }

    public async Task<string> DownloadAsync(
        WindowsUpdateInfo update,
        IProgress<int>? progress = null,
        CancellationToken cancellationToken = default)
    {
        var directory = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "CodexDesktopRemote",
            "updates");
        Directory.CreateDirectory(directory);
        var destination = Path.Combine(directory, Path.GetFileName(update.AssetName));
        var pending = destination + ".part";
        try
        {
            using var response = await _http.GetAsync(
                update.DownloadUrl,
                HttpCompletionOption.ResponseHeadersRead,
                cancellationToken);
            response.EnsureSuccessStatusCode();
            var total = response.Content.Headers.ContentLength ?? update.Size;
            await using var input = await response.Content.ReadAsStreamAsync(cancellationToken);
            await using var output = new FileStream(
                pending,
                FileMode.Create,
                FileAccess.Write,
                FileShare.None,
                81920,
                useAsync: true);
            var buffer = new byte[81920];
            long received = 0;
            while (true)
            {
                var count = await input.ReadAsync(buffer, cancellationToken);
                if (count == 0) break;
                await output.WriteAsync(buffer.AsMemory(0, count), cancellationToken);
                received += count;
                if (total > 0) progress?.Report((int)Math.Clamp(received * 100 / total, 0, 100));
            }
            await output.FlushAsync(cancellationToken);
            File.Move(pending, destination, overwrite: true);
            progress?.Report(100);
            return destination;
        }
        finally
        {
            if (File.Exists(pending)) File.Delete(pending);
        }
    }

    internal static bool IsNewerVersion(Version currentVersion, string tagName)
    {
        var raw = tagName.Trim().TrimStart('v', 'V');
        return Version.TryParse(raw, out var available) && available > currentVersion;
    }

    internal static GitHubReleaseAsset? SelectWindowsAsset(IEnumerable<GitHubReleaseAsset> assets) =>
        assets.FirstOrDefault(asset =>
            asset.Name.StartsWith("CodexRemote-Windows-x64-", StringComparison.OrdinalIgnoreCase) &&
            asset.Name.EndsWith(".zip", StringComparison.OrdinalIgnoreCase));

    public void Dispose() => _http.Dispose();
}

public sealed record WindowsUpdateInfo(
    string Version,
    string ReleaseUrl,
    string Notes,
    string AssetName,
    string DownloadUrl,
    long Size);

internal sealed record GitHubRelease(
    [property: JsonPropertyName("tag_name")] string TagName,
    [property: JsonPropertyName("html_url")] string HtmlUrl,
    [property: JsonPropertyName("body")] string? Body,
    [property: JsonPropertyName("draft")] bool Draft,
    [property: JsonPropertyName("prerelease")] bool Prerelease,
    [property: JsonPropertyName("assets")] IReadOnlyList<GitHubReleaseAsset> Assets);

internal sealed record GitHubReleaseAsset(
    [property: JsonPropertyName("name")] string Name,
    [property: JsonPropertyName("browser_download_url")] string BrowserDownloadUrl,
    [property: JsonPropertyName("size")] long Size);
