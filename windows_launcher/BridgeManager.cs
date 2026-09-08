using System.Diagnostics;
using System.IO;
using System.Net.NetworkInformation;

namespace CodexRemoteManager;

public sealed class BridgeManager : IDisposable
{
    private readonly LocalBridgeClient _client;
    private readonly string _buildId;
    private Process? _ownedProcess;

    public BridgeManager(LocalBridgeClient client, string buildId)
    {
        _client = client;
        _buildId = buildId;
    }

    public event Action<string>? LogReceived;

    public async Task<LocalBridgeStatus> StartAsync(int port, CancellationToken cancellationToken = default)
    {
        var existing = await _client.GetStatusAsync(port, cancellationToken);
        if (existing is not null)
        {
            if (!RequiresBridgeRestart(existing.Bridge.BuildId, _buildId))
            {
                Log("已连接到当前版本的 Bridge，手机授权保持不变。");
                return existing;
            }
            Log("检测到旧版 Bridge，正在切换到当前版本；手机授权保持不变。");
            await StopAsync(port, cancellationToken);
        }

        if (IsPortListening(port))
        {
            var recovered = await TryRecoverUnresponsiveBridgeAsync(port, cancellationToken);
            if (!recovered)
                throw new InvalidOperationException($"端口 {port} 已被其他程序占用，未执行强制关闭。");
        }

        var bridgeRoot = FindBridgeRoot();
        await EnsureBridgeBuiltAsync(bridgeRoot, cancellationToken);
        var node = FindNodeExecutable();
        var entry = Path.Combine(bridgeRoot, "dist", "index.js");
        var info = new ProcessStartInfo(node, $"\"{entry}\"")
        {
            WorkingDirectory = bridgeRoot,
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
        };
        info.Environment["BRIDGE_HOST"] = "0.0.0.0";
        info.Environment["BRIDGE_PORT"] = port.ToString();
        info.Environment["BRIDGE_BUILD_ID"] = _buildId;
        info.Environment["BRIDGE_DATA_DIR"] = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "OneSCodexRemote");

        var process = new Process { StartInfo = info, EnableRaisingEvents = true };
        process.OutputDataReceived += (_, e) => { if (!string.IsNullOrWhiteSpace(e.Data)) Log(e.Data); };
        process.ErrorDataReceived += (_, e) => { if (!string.IsNullOrWhiteSpace(e.Data)) Log(e.Data); };
        process.Exited += (_, _) => HandleBridgeExited(process);
        _ownedProcess = process;
        if (!process.Start()) throw new InvalidOperationException("无法启动 Bridge 进程。");
        process.BeginOutputReadLine();
        process.BeginErrorReadLine();
        Log($"正在启动 Bridge，监听 0.0.0.0:{port} ...");

        var deadline = DateTimeOffset.UtcNow.AddSeconds(20);
        while (DateTimeOffset.UtcNow < deadline)
        {
            cancellationToken.ThrowIfCancellationRequested();
            if (process.HasExited)
                throw new InvalidOperationException($"Bridge 启动失败，退出代码 {process.ExitCode}。");
            var status = await _client.GetStatusAsync(port, cancellationToken);
            if (status is not null) return status;
            await Task.Delay(350, cancellationToken);
        }
        throw new TimeoutException("Bridge 在 20 秒内没有开始响应。");
    }

    public async Task StopAsync(int port, CancellationToken cancellationToken = default)
    {
        var status = await _client.GetStatusAsync(port, cancellationToken);
        if (status is null)
        {
            Log("Bridge 当前没有运行。");
            return;
        }

        await _client.RequestShutdownAsync(port, cancellationToken);
        var deadline = DateTimeOffset.UtcNow.AddSeconds(10);
        while (DateTimeOffset.UtcNow < deadline && await _client.GetStatusAsync(port, cancellationToken) is not null)
            await Task.Delay(250, cancellationToken);
        Log("Bridge 已停止。已有手机授权仍保存在电脑中。");
    }

    public static void OpenCodexDesktop()
    {
        Process.Start(new ProcessStartInfo("explorer.exe",
            "shell:AppsFolder\\OpenAI.Codex_2p2nqsd0c76g0!App") { UseShellExecute = true });
    }

    private async Task<bool> TryRecoverUnresponsiveBridgeAsync(int port, CancellationToken cancellationToken)
    {
        var expectedEntries = FindKnownBridgeEntries();
        if (expectedEntries.Count == 0) return false;
        var commandLineMatches = string.Join(" -or ", expectedEntries.Select(entry =>
            "$w.CommandLine -like '*" + entry.Replace("'", "''") + "*'"));
        var command = "$p=(Get-NetTCPConnection -State Listen -LocalPort " + port +
            " -ErrorAction SilentlyContinue|Select-Object -First 1).OwningProcess;" +
            "if($p){$w=Get-CimInstance Win32_Process -Filter \"ProcessId = $p\";" +
            "if($w.Name -eq 'node.exe' -and (" + commandLineMatches + ")){Write-Output $p}}";
        var info = new ProcessStartInfo("powershell.exe")
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
        };
        info.ArgumentList.Add("-NoProfile");
        info.ArgumentList.Add("-NonInteractive");
        info.ArgumentList.Add("-Command");
        info.ArgumentList.Add(command);
        using var lookup = Process.Start(info);
        if (lookup is null) return false;
        var output = await lookup.StandardOutput.ReadToEndAsync(cancellationToken);
        await lookup.WaitForExitAsync(cancellationToken);
        if (!int.TryParse(output.Trim(), out var pid)) return false;

        Process.GetProcessById(pid).Kill(true);
        Log($"已恢复无响应的 Bridge（旧进程 {pid}）。");
        for (var i = 0; i < 40 && IsPortListening(port); i++)
            await Task.Delay(250, cancellationToken);
        return !IsPortListening(port);
    }

    private async Task EnsureBridgeBuiltAsync(string bridgeRoot, CancellationToken cancellationToken)
    {
        if (File.Exists(Path.Combine(bridgeRoot, "dist", "index.js"))) return;
        if (!File.Exists(Path.Combine(bridgeRoot, "package.json")))
            throw new FileNotFoundException("安装包中缺少 Bridge 文件。请重新下载完整的 Windows 版本。");

        Log("首次运行，正在准备 Bridge ...");
        await RunToolAsync("npm.cmd", "run", "build", bridgeRoot, cancellationToken);
    }

    private static async Task RunToolAsync(string fileName, string argument1, string argument2,
        string workingDirectory, CancellationToken cancellationToken)
    {
        var info = new ProcessStartInfo(fileName)
        {
            WorkingDirectory = workingDirectory,
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardError = true,
        };
        info.ArgumentList.Add(argument1);
        info.ArgumentList.Add(argument2);
        using var process = Process.Start(info) ?? throw new InvalidOperationException($"无法运行 {fileName}。");
        var error = await process.StandardError.ReadToEndAsync(cancellationToken);
        await process.WaitForExitAsync(cancellationToken);
        if (process.ExitCode != 0) throw new InvalidOperationException($"Bridge 构建失败：{error.Trim()}");
    }

    private static string FindBridgeRoot()
    {
        var direct = Path.Combine(AppContext.BaseDirectory, "bridge");
        if (File.Exists(Path.Combine(direct, "dist", "index.js"))) return direct;

        var directory = new DirectoryInfo(AppContext.BaseDirectory);
        for (var i = 0; i < 8 && directory is not null; i++, directory = directory.Parent)
        {
            var candidate = Path.Combine(directory.FullName, "desktop_bridge");
            if (File.Exists(Path.Combine(candidate, "package.json"))) return candidate;
        }
        throw new DirectoryNotFoundException("找不到 desktop_bridge。请使用完整 Windows 安装包。");
    }

    private static IReadOnlyList<string> FindKnownBridgeEntries()
    {
        var entries = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var packaged = Path.Combine(AppContext.BaseDirectory, "bridge", "dist", "index.js");
        if (File.Exists(packaged)) entries.Add(Path.GetFullPath(packaged));

        var directory = new DirectoryInfo(AppContext.BaseDirectory);
        for (var i = 0; i < 8 && directory is not null; i++, directory = directory.Parent)
        {
            var development = Path.Combine(directory.FullName, "desktop_bridge", "dist", "index.js");
            if (File.Exists(development)) entries.Add(Path.GetFullPath(development));
        }
        return entries.ToArray();
    }

    private static string FindNodeExecutable()
    {
        var bundled = Path.Combine(AppContext.BaseDirectory, "runtime", "node.exe");
        if (File.Exists(bundled)) return bundled;
        var known = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles),
            "node-win-x64", "node.exe");
        if (File.Exists(known)) return known;
        return "node.exe";
    }

    private static bool IsPortListening(int port) =>
        IPGlobalProperties.GetIPGlobalProperties().GetActiveTcpListeners().Any(endpoint => endpoint.Port == port);

    internal static bool RequiresBridgeRestart(string? runningBuildId, string expectedBuildId) =>
        !string.Equals(runningBuildId, expectedBuildId, StringComparison.Ordinal);

    private void HandleBridgeExited(Process process)
    {
        try
        {
            string message;
            try
            {
                process.WaitForExit();
                message = $"Bridge 已退出（代码 {process.ExitCode}）。";
            }
            catch (ObjectDisposedException)
            {
                message = "Bridge 已退出。";
            }
            catch (InvalidOperationException)
            {
                message = "Bridge 已退出（无法读取退出代码）。";
            }
            Log(message);
        }
        catch
        {
            // Process exit notifications must never terminate the tray manager.
        }
    }

    private void Log(string message) => LogReceived?.Invoke($"{DateTime.Now:HH:mm:ss}  {message}");

    public void Dispose()
    {
        var process = Interlocked.Exchange(ref _ownedProcess, null);
        if (process is null) return;
        try { process.EnableRaisingEvents = false; }
        catch (ObjectDisposedException) { }
        catch (InvalidOperationException) { }
        process.Dispose();
    }
}
