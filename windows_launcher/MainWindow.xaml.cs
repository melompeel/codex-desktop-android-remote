using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Navigation;
using System.Windows.Threading;
using Forms = System.Windows.Forms;
using Button = System.Windows.Controls.Button;
using Clipboard = System.Windows.Clipboard;
using Color = System.Windows.Media.Color;
using ColorConverter = System.Windows.Media.ColorConverter;
using MessageBox = System.Windows.MessageBox;

namespace CodexRemoteManager;

public partial class MainWindow : Window
{
    private static readonly string VersionLabel = BuildVersionLabel();
    private static readonly Version CurrentVersion = BuildVersion();
    private readonly LauncherSettingsStore _settingsStore = new();
    private readonly LocalBridgeClient _client = new();
    private readonly GitHubUpdateService _updateService = new();
    private readonly BridgeManager _manager;
    private readonly DispatcherTimer _pollTimer = new() { Interval = TimeSpan.FromSeconds(2) };
    private readonly DispatcherTimer _countdownTimer = new() { Interval = TimeSpan.FromSeconds(1) };
    private readonly ObservableCollection<BridgeAddress> _addresses = [];
    private readonly ObservableCollection<DeviceInfo> _devices = [];
    private readonly Forms.NotifyIcon _trayIcon;
    private LauncherSettings _settings;
    private LocalBridgeStatus? _status;
    private bool _allowExit;
    private bool _initializing = true;
    private bool _busy;
    private bool _statusCheckInProgress;
    private bool _restartWhenMissing;
    private int _missingHealthChecks;
    private DateTimeOffset _nextAutomaticRestartAt = DateTimeOffset.MinValue;
    private WindowsUpdateInfo? _availableUpdate;
    private string? _downloadedUpdatePath;
    private bool _checkingUpdate;

    public MainWindow(bool smokeMode = false)
    {
        InitializeComponent();
        ManagerVersionText.Text = VersionLabel;
        UpdateStatusText.Text = $"当前版本 v{CurrentVersion.ToString(3)}";
        Title = $"Codex Remote 管理器 {VersionLabel}";
        _settings = _settingsStore.Load();
        _restartWhenMissing = _settings.StartBridgeOnLaunch;
        PortTextBox.Text = _settings.Port.ToString();
        CloseToTrayCheckBox.IsChecked = _settings.CloseToTray;
        AutostartCheckBox.IsChecked = AutostartService.IsEnabled();
        AddressItems.ItemsSource = _addresses;
        DeviceItems.ItemsSource = _devices;

        _manager = new BridgeManager(_client, VersionLabel);
        _manager.LogReceived += AppendLog;
        AppendLog($"Windows 管理器 {VersionLabel}");
        _pollTimer.Tick += async (_, _) => await RefreshAndRecoverAsync();
        _countdownTimer.Tick += (_, _) => UpdateCountdown();
        _trayIcon = CreateTrayIcon();
        _initializing = false;

        if (!smokeMode) Loaded += async (_, _) =>
        {
            _pollTimer.Start();
            _countdownTimer.Start();
            if (_settings.StartBridgeOnLaunch)
                await RunOperationAsync(() => _manager.StartAsync(CurrentPort));
            else
                await RefreshStatusAsync();
            _ = CheckForUpdatesAsync(interactive: false);
            if (Environment.GetCommandLineArgs().Contains("--minimized", StringComparer.OrdinalIgnoreCase))
                HideToTray(showNotice: false);
        };
    }

    private int CurrentPort
    {
        get
        {
            if (!int.TryParse(PortTextBox.Text, out var port) || port is < 1 or > 65535)
                throw new InvalidOperationException("端口必须是 1 到 65535 之间的数字。");
            return port;
        }
    }

    private async Task RefreshStatusAsync()
    {
        if (_busy || _statusCheckInProgress) return;
        _statusCheckInProgress = true;
        try
        {
            int port;
            try { port = CurrentPort; }
            catch { return; }
            var status = await _client.GetStatusAsync(port);
            ApplyStatus(status);
        }
        finally
        {
            _statusCheckInProgress = false;
        }
    }

    private async Task RefreshAndRecoverAsync()
    {
        if (_busy || _statusCheckInProgress) return;
        _statusCheckInProgress = true;
        try
        {
            int port;
            try { port = CurrentPort; }
            catch { return; }
            var status = await _client.GetStatusAsync(port);
            ApplyStatus(status);
            if (status is not null)
            {
                _missingHealthChecks = 0;
                _nextAutomaticRestartAt = DateTimeOffset.MinValue;
                return;
            }
            if (!_restartWhenMissing || DateTimeOffset.UtcNow < _nextAutomaticRestartAt) return;
            _missingHealthChecks += 1;
            if (_missingHealthChecks < 3) return;

            _missingHealthChecks = 0;
            try
            {
                SetBusy(true);
                AppendLog("Bridge 连续三次未响应，正在自动恢复...");
                var recovered = await _manager.StartAsync(port);
                ApplyStatus(recovered);
                AppendLog("Bridge 已自动恢复，手机授权保持不变。");
            }
            catch (Exception error)
            {
                _nextAutomaticRestartAt = DateTimeOffset.UtcNow.AddSeconds(15);
                AppendLog($"Bridge 自动恢复失败，15 秒后重试：{error.Message}");
                ApplyStatus(null);
            }
            finally
            {
                SetBusy(false);
            }
        }
        finally
        {
            _statusCheckInProgress = false;
        }
    }

    private void ApplyStatus(LocalBridgeStatus? status)
    {
        _status = status;
        var running = status is not null;
        OverallDot.Fill = new SolidColorBrush((Color)ColorConverter.ConvertFromString(
            running && status!.Ipc == "connected" ? "#19A974" : running ? "#E09F3E" : "#9AA3A0"));
        OverallStatusText.Text = running
            ? status!.Ipc == "connected" ? "运行正常" : "Bridge 已启动，等待桌面连接"
            : "Bridge 未运行";
        BridgeStatusText.Text = running ? $"运行中  PID {status!.Bridge.Pid}" : "未运行";
        IpcStatusText.Text = running ? IpcLabel(status!.Ipc) : "--";
        DesktopVersionText.Text = status?.Bridge.DesktopVersion ?? "--";
        DeviceCountText.Text = running ? $"{status!.Devices.Count} 台" : "--";
        _devices.Clear();
        foreach (var device in status?.Devices ?? []) _devices.Add(device);
        DeviceExpander.IsEnabled = _devices.Count > 0;
        if (_devices.Count == 0) DeviceExpander.IsExpanded = false;
        PairingCodeText.Text = status?.Pairing.Code ?? "------";
        ListenIpText.Text = status?.Bridge.Host ?? "0.0.0.0";
        FooterText.Text = running
            ? $"监听 {status!.Bridge.Host}:{status.Bridge.Port} · Node {status.Bridge.RuntimeVersion ?? "--"}"
            : "Bridge 未运行";

        _addresses.Clear();
        foreach (var address in status?.Bridge.Addresses ?? []) _addresses.Add(address);
        NoAddressText.Visibility = _addresses.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        StartButton.IsEnabled = !running && !_busy;
        StopButton.IsEnabled = running && !_busy;
        RestartButton.IsEnabled = running && !_busy;
        PortTextBox.IsEnabled = !running && !_busy;
        UpdateCountdown();
    }

    private void UpdateCountdown()
    {
        if (_status is null)
        {
            PairingExpiryText.Text = "启动后显示";
            return;
        }
        var remaining = DateTimeOffset.FromUnixTimeMilliseconds(_status.Pairing.ExpiresAt) - DateTimeOffset.Now;
        PairingExpiryText.Text = remaining > TimeSpan.Zero
            ? $"{remaining.Minutes:00}:{remaining.Seconds:00} 后过期"
            : "已过期，请换一个配对码";
    }

    private async Task RunOperationAsync(Func<Task<LocalBridgeStatus>> operation)
    {
        if (_busy) return;
        try
        {
            SetBusy(true);
            var status = await operation();
            ApplyStatus(status);
        }
        catch (Exception error)
        {
            AppendLog(error.Message);
            MessageBox.Show(this, error.Message, "Codex Remote", MessageBoxButton.OK, MessageBoxImage.Warning);
        }
        finally
        {
            SetBusy(false);
            await RefreshStatusAsync();
        }
    }

    private void SetBusy(bool busy)
    {
        _busy = busy;
        StartButton.IsEnabled = !busy && _status is null;
        StopButton.IsEnabled = !busy && _status is not null;
        RestartButton.IsEnabled = !busy && _status is not null;
        PortTextBox.IsEnabled = !busy && _status is null;
        if (busy) OverallStatusText.Text = "正在处理";
    }

    private async void Start_Click(object sender, RoutedEventArgs e)
    {
        SavePort();
        _restartWhenMissing = true;
        await RunOperationAsync(() => _manager.StartAsync(CurrentPort));
    }

    private async void Stop_Click(object sender, RoutedEventArgs e)
    {
        if (_busy) return;
        _restartWhenMissing = false;
        try
        {
            SetBusy(true);
            await _manager.StopAsync(CurrentPort);
            ApplyStatus(null);
        }
        catch (Exception error)
        {
            MessageBox.Show(this, error.Message, "Codex Remote", MessageBoxButton.OK, MessageBoxImage.Warning);
        }
        finally { SetBusy(false); }
    }

    private async void Restart_Click(object sender, RoutedEventArgs e)
    {
        if (_busy) return;
        _restartWhenMissing = true;
        try
        {
            SetBusy(true);
            await _manager.StopAsync(CurrentPort);
            var status = await _manager.StartAsync(CurrentPort);
            ApplyStatus(status);
        }
        catch (Exception error)
        {
            AppendLog(error.Message);
            MessageBox.Show(this, error.Message, "Codex Remote", MessageBoxButton.OK, MessageBoxImage.Warning);
        }
        finally { SetBusy(false); }
    }

    private async void RotatePairing_Click(object sender, RoutedEventArgs e)
    {
        if (_status is null) return;
        try
        {
            var pairing = await _client.RotatePairingAsync(CurrentPort);
            _status = _status with { Pairing = pairing };
            PairingCodeText.Text = pairing.Code;
            UpdateCountdown();
            AppendLog("已生成新的临时配对码，现有手机授权没有变化。");
        }
        catch (Exception error)
        {
            MessageBox.Show(this, error.Message, "Codex Remote", MessageBoxButton.OK, MessageBoxImage.Warning);
        }
    }

    private void CopyPairing_Click(object sender, RoutedEventArgs e)
    {
        if (_status is null) return;
        if (TryCopyToClipboard(_status.Pairing.Code)) FooterText.Text = "配对码已复制";
    }

    private void CopyAddress_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button { Tag: string url })
        {
            if (TryCopyToClipboard(url)) FooterText.Text = $"已复制 {url}";
        }
    }

    private bool TryCopyToClipboard(string text)
    {
        for (var attempt = 0; attempt < 5; attempt += 1)
        {
            try
            {
                Clipboard.SetText(text);
                return true;
            }
            catch (COMException) when (attempt < 4)
            {
                Thread.Sleep(20 * (attempt + 1));
            }
            catch (COMException)
            {
                break;
            }
        }
        FooterText.Text = "剪贴板正被其他程序占用，请稍后重试";
        AppendLog("复制失败：Windows 剪贴板正被其他程序占用。");
        return false;
    }

    private async void RevokeDevice_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not Button { Tag: DeviceInfo device } || _busy) return;
        var confirmed = MessageBox.Show(this,
            $"确定删除“{device.Name}”的授权吗？\n\n该设备会立即断开，下次连接需要重新输入配对码。其他设备不受影响。",
            "删除授权设备", MessageBoxButton.YesNo, MessageBoxImage.Warning,
            MessageBoxResult.No);
        if (confirmed != MessageBoxResult.Yes) return;

        try
        {
            SetBusy(true);
            await _client.RevokeDeviceAsync(CurrentPort, device.DeviceId);
            AppendLog($"已删除授权设备：{device.Name}");
        }
        catch (Exception error)
        {
            MessageBox.Show(this, error.Message, "Codex Remote",
                MessageBoxButton.OK, MessageBoxImage.Warning);
        }
        finally
        {
            SetBusy(false);
            await RefreshStatusAsync();
        }
    }

    private void OpenCodex_Click(object sender, RoutedEventArgs e)
    {
        try { BridgeManager.OpenCodexDesktop(); }
        catch (Exception error) { MessageBox.Show(this, error.Message, "Codex Remote"); }
    }

    private async void CheckUpdate_Click(object sender, RoutedEventArgs e) =>
        await CheckForUpdatesAsync(interactive: true);

    private async Task CheckForUpdatesAsync(bool interactive)
    {
        if (_checkingUpdate) return;
        _checkingUpdate = true;
        CheckUpdateButton.IsEnabled = false;
        UpdateStatusText.Text = "正在检查 GitHub 最新版本...";
        try
        {
            _availableUpdate = await _updateService.CheckAsync(CurrentVersion);
            _downloadedUpdatePath = null;
            OpenUpdateButton.Visibility = Visibility.Collapsed;
            if (_availableUpdate is null)
            {
                DownloadUpdateButton.Visibility = Visibility.Collapsed;
                UpdateStatusText.Text = $"当前已是最新版本 v{CurrentVersion.ToString(3)}";
                if (interactive)
                    MessageBox.Show(this, "当前已经是最新版本。", "软件更新",
                        MessageBoxButton.OK, MessageBoxImage.Information);
                return;
            }
            DownloadUpdateButton.Visibility = Visibility.Visible;
            DownloadUpdateButton.IsEnabled = true;
            UpdateStatusText.Text = $"发现新版本 v{_availableUpdate.Version} · Windows x64";
        }
        catch (Exception error)
        {
            UpdateStatusText.Text = "暂时无法检查更新";
            AppendLog($"检查更新失败：{error.Message}");
            if (interactive)
                MessageBox.Show(this, error.Message, "软件更新",
                    MessageBoxButton.OK, MessageBoxImage.Warning);
        }
        finally
        {
            _checkingUpdate = false;
            CheckUpdateButton.IsEnabled = true;
        }
    }

    private async void DownloadUpdate_Click(object sender, RoutedEventArgs e)
    {
        var update = _availableUpdate;
        if (update is null) return;
        DownloadUpdateButton.IsEnabled = false;
        CheckUpdateButton.IsEnabled = false;
        UpdateProgressBar.Value = 0;
        UpdateProgressBar.Visibility = Visibility.Visible;
        try
        {
            var progress = new Progress<int>(value =>
            {
                UpdateProgressBar.Value = value;
                UpdateStatusText.Text = $"正在下载 v{update.Version} · {value}%";
            });
            _downloadedUpdatePath = await _updateService.DownloadAsync(update, progress);
            UpdateStatusText.Text = $"v{update.Version} 已下载，关闭管理器后解压覆盖即可更新";
            DownloadUpdateButton.Visibility = Visibility.Collapsed;
            OpenUpdateButton.Visibility = Visibility.Visible;
            AppendLog($"Windows 更新包已下载：{Path.GetFileName(_downloadedUpdatePath)}");
        }
        catch (Exception error)
        {
            UpdateStatusText.Text = $"v{update.Version} 下载失败，可重试";
            DownloadUpdateButton.IsEnabled = true;
            MessageBox.Show(this, error.Message, "下载更新",
                MessageBoxButton.OK, MessageBoxImage.Warning);
        }
        finally
        {
            UpdateProgressBar.Visibility = Visibility.Collapsed;
            CheckUpdateButton.IsEnabled = true;
        }
    }

    private void OpenUpdate_Click(object sender, RoutedEventArgs e)
    {
        if (_downloadedUpdatePath is not { } path || !File.Exists(path)) return;
        Process.Start(new ProcessStartInfo("explorer.exe", $"/select,\"{path}\"")
        {
            UseShellExecute = true,
        });
    }

    private void Link_RequestNavigate(object sender, RequestNavigateEventArgs e)
    {
        try
        {
            Process.Start(new ProcessStartInfo(e.Uri.AbsoluteUri) { UseShellExecute = true });
        }
        catch (Exception error)
        {
            AppendLog($"打开链接失败：{error.Message}");
            MessageBox.Show(this, "无法打开系统默认应用。", "Codex Remote",
                MessageBoxButton.OK, MessageBoxImage.Warning);
        }
        e.Handled = true;
    }

    private void Autostart_Changed(object sender, RoutedEventArgs e)
    {
        if (_initializing) return;
        try { AutostartService.SetEnabled(AutostartCheckBox.IsChecked == true); }
        catch (Exception error)
        {
            MessageBox.Show(this, error.Message, "Codex Remote", MessageBoxButton.OK, MessageBoxImage.Warning);
        }
    }

    private void CloseToTray_Changed(object sender, RoutedEventArgs e)
    {
        if (_initializing) return;
        _settings = _settings with { CloseToTray = CloseToTrayCheckBox.IsChecked == true };
        _settingsStore.Save(_settings);
    }

    private void SavePort()
    {
        _settings = _settings with { Port = CurrentPort };
        _settingsStore.Save(_settings);
    }

    private Forms.NotifyIcon CreateTrayIcon()
    {
        var menu = new Forms.ContextMenuStrip();
        menu.Items.Add("打开管理器", null, (_, _) => Dispatcher.Invoke(ShowFromTray));
        menu.Items.Add("启动 Bridge", null, async (_, _) => await Dispatcher.InvokeAsync(
            async () =>
            {
                _restartWhenMissing = true;
                await RunOperationAsync(() => _manager.StartAsync(CurrentPort));
            }));
        menu.Items.Add("停止 Bridge", null, (_, _) => Dispatcher.Invoke(
            () => Stop_Click(this, new RoutedEventArgs())));
        menu.Items.Add(new Forms.ToolStripSeparator());
        menu.Items.Add("退出管理器", null, (_, _) => Dispatcher.Invoke(ExitApplication));
        var icon = new Forms.NotifyIcon
        {
            Text = "Codex Remote 管理器",
            Icon = Environment.ProcessPath is { } executable
                ? System.Drawing.Icon.ExtractAssociatedIcon(executable)
                    ?? System.Drawing.SystemIcons.Application
                : System.Drawing.SystemIcons.Application,
            Visible = true,
            ContextMenuStrip = menu,
        };
        icon.DoubleClick += (_, _) => Dispatcher.Invoke(ShowFromTray);
        return icon;
    }

    private void Window_Closing(object? sender, CancelEventArgs e)
    {
        if (_allowExit) return;
        if (CloseToTrayCheckBox.IsChecked == true)
        {
            e.Cancel = true;
            HideToTray(showNotice: true);
        }
    }

    private void HideToTray(bool showNotice)
    {
        Hide();
        if (showNotice)
            _trayIcon.ShowBalloonTip(2500, "Codex Remote 仍在运行",
                "手机连接不会中断。双击托盘图标可重新打开。", Forms.ToolTipIcon.Info);
    }

    private void ShowFromTray()
    {
        Show();
        WindowState = WindowState.Normal;
        Activate();
    }

    private void ExitApplication()
    {
        _allowExit = true;
        _trayIcon.Visible = false;
        Close();
    }

    private void AppendLog(string message)
    {
        Dispatcher.Invoke(() =>
        {
            LogTextBox.AppendText(message + Environment.NewLine);
            LogTextBox.ScrollToEnd();
            try
            {
                var directory = Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
                    "CodexDesktopRemote");
                Directory.CreateDirectory(directory);
                File.AppendAllText(Path.Combine(directory, "manager.log"),
                    message + Environment.NewLine);
            }
            catch
            {
                // The in-window log remains available if the optional file log cannot be written.
            }
        });
    }

    protected override void OnClosed(EventArgs e)
    {
        _pollTimer.Stop();
        _countdownTimer.Stop();
        _trayIcon.Dispose();
        _manager.Dispose();
        _client.Dispose();
        _updateService.Dispose();
        base.OnClosed(e);
    }

    internal void RunDeviceListSmokeTest()
    {
        if (!ManagerVersionText.Text.StartsWith("v", StringComparison.Ordinal))
            throw new InvalidOperationException("manager-version-label-missing");
        if (!BridgeManager.RequiresBridgeRestart(null, VersionLabel) ||
            BridgeManager.RequiresBridgeRestart(VersionLabel, VersionLabel))
            throw new InvalidOperationException("bridge-build-comparison-smoke-failed");
        if (!GitHubUpdateService.IsNewerVersion(new Version(0, 5, 3), "v0.5.4") ||
            GitHubUpdateService.IsNewerVersion(new Version(0, 5, 4), "v0.5.4"))
            throw new InvalidOperationException("windows-update-version-comparison-smoke-failed");
        var updateAsset = GitHubUpdateService.SelectWindowsAsset([
            new GitHubReleaseAsset("CodexRemote-Android-v0.5.4-debug.apk", "https://example.invalid/android", 1),
            new GitHubReleaseAsset("CodexRemote-Windows-x64-v0.5.4.zip", "https://example.invalid/windows", 2),
        ]);
        if (updateAsset?.Name != "CodexRemote-Windows-x64-v0.5.4.zip")
            throw new InvalidOperationException("windows-update-asset-selection-smoke-failed");
        var device = new DeviceInfo(
            "smoke-device", "Smoke Android", "android", DateTimeOffset.Now.ToUnixTimeMilliseconds());
        var run = new System.Windows.Documents.Run { DataContext = device };
        run.SetBinding(System.Windows.Documents.Run.TextProperty,
            new System.Windows.Data.Binding(nameof(DeviceInfo.KindLabel)) {
                Mode = System.Windows.Data.BindingMode.OneWay,
            });
        run.GetBindingExpression(System.Windows.Documents.Run.TextProperty)?.UpdateTarget();
        if (run.Text != "Android") throw new InvalidOperationException("device-binding-smoke-failed");
        _devices.Add(device);
        DeviceExpander.IsEnabled = true;
        DeviceExpander.IsExpanded = true;
        Show();
        Dispatcher.Invoke(System.Windows.Threading.DispatcherPriority.ApplicationIdle, () => { });
    }

    internal void CloseForSmokeTest()
    {
        _allowExit = true;
        Close();
    }

    private static string IpcLabel(string status) => status switch
    {
        "connected" => "已连接",
        "connecting" => "正在连接",
        "disconnected" => "未连接",
        _ => status,
    };

    private static string BuildVersionLabel()
    {
        var assembly = typeof(MainWindow).Assembly;
        var informational = assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()
            ?.InformationalVersion;
        var fallback = assembly.GetName().Version?.ToString(3) ?? "0.0.0";
        var value = string.IsNullOrWhiteSpace(informational) ? fallback : informational;
        var parts = value.Split('+', 2, StringSplitOptions.TrimEntries);
        if (parts.Length < 2 || string.IsNullOrWhiteSpace(parts[1])) return $"v{parts[0]}";
        var revision = parts[1].Length > 7 ? parts[1][..7] : parts[1];
        return $"v{parts[0]} · {revision}";
    }

    private static Version BuildVersion() =>
        typeof(MainWindow).Assembly.GetName().Version ?? new Version(0, 0, 0);
}
