using System.Collections.ObjectModel;
using System.ComponentModel;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
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
    private readonly LauncherSettingsStore _settingsStore = new();
    private readonly LocalBridgeClient _client = new();
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

    public MainWindow()
    {
        InitializeComponent();
        _settings = _settingsStore.Load();
        PortTextBox.Text = _settings.Port.ToString();
        CloseToTrayCheckBox.IsChecked = _settings.CloseToTray;
        AutostartCheckBox.IsChecked = AutostartService.IsEnabled();
        AddressItems.ItemsSource = _addresses;
        DeviceItems.ItemsSource = _devices;

        _manager = new BridgeManager(_client);
        _manager.LogReceived += AppendLog;
        _pollTimer.Tick += async (_, _) => await RefreshStatusAsync();
        _countdownTimer.Tick += (_, _) => UpdateCountdown();
        _trayIcon = CreateTrayIcon();
        _initializing = false;

        Loaded += async (_, _) =>
        {
            _pollTimer.Start();
            _countdownTimer.Start();
            await RefreshStatusAsync();
            if (_settings.StartBridgeOnLaunch && _status is null)
                await RunOperationAsync(() => _manager.StartAsync(CurrentPort));
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
        if (_busy) return;
        int port;
        try { port = CurrentPort; }
        catch { return; }
        var status = await _client.GetStatusAsync(port);
        ApplyStatus(status);
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
        await RunOperationAsync(() => _manager.StartAsync(CurrentPort));
    }

    private async void Stop_Click(object sender, RoutedEventArgs e)
    {
        if (_busy) return;
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
        Clipboard.SetText(_status.Pairing.Code);
        FooterText.Text = "配对码已复制";
    }

    private void CopyAddress_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button { Tag: string url })
        {
            Clipboard.SetText(url);
            FooterText.Text = $"已复制 {url}";
        }
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
            async () => await RunOperationAsync(() => _manager.StartAsync(CurrentPort))));
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
        base.OnClosed(e);
    }

    private static string IpcLabel(string status) => status switch
    {
        "connected" => "已连接",
        "connecting" => "正在连接",
        "disconnected" => "未连接",
        _ => status,
    };
}
