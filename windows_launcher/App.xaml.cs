using System.Threading;
using System.Windows;
using MessageBox = System.Windows.MessageBox;

namespace CodexRemoteManager;

public partial class App : System.Windows.Application
{
    private Mutex? _singleInstance;

    protected override void OnStartup(StartupEventArgs e)
    {
        var smokeDeviceList = e.Args.Contains("--smoke-device-list", StringComparer.OrdinalIgnoreCase);
        var mutexName = smokeDeviceList
            ? $"CodexDesktopRemoteManager.Smoke.{Environment.ProcessId}"
            : "CodexDesktopRemoteManager.SingleInstance";
        _singleInstance = new Mutex(true, mutexName, out var createdNew);
        if (!createdNew)
        {
            MessageBox.Show("Codex Remote 管理器已经在运行。请查看任务栏右下角的托盘图标。",
                "Codex Remote", MessageBoxButton.OK, MessageBoxImage.Information);
            Shutdown();
            return;
        }

        base.OnStartup(e);
        var window = new MainWindow(smokeDeviceList);
        if (smokeDeviceList)
        {
            try
            {
                window.RunDeviceListSmokeTest();
                window.CloseForSmokeTest();
                Shutdown(0);
            }
            catch
            {
                window.CloseForSmokeTest();
                Environment.ExitCode = 1;
                Shutdown(1);
            }
            return;
        }

        MainWindow = window;
        window.Show();
    }

    protected override void OnExit(ExitEventArgs e)
    {
        _singleInstance?.ReleaseMutex();
        _singleInstance?.Dispose();
        base.OnExit(e);
    }
}
