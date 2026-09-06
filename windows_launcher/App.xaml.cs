using System.Threading;
using System.Windows;
using MessageBox = System.Windows.MessageBox;

namespace CodexRemoteManager;

public partial class App : System.Windows.Application
{
    private Mutex? _singleInstance;

    protected override void OnStartup(StartupEventArgs e)
    {
        _singleInstance = new Mutex(true, "CodexDesktopRemoteManager.SingleInstance", out var createdNew);
        if (!createdNew)
        {
            MessageBox.Show("Codex Remote 管理器已经在运行。请查看任务栏右下角的托盘图标。",
                "Codex Remote", MessageBoxButton.OK, MessageBoxImage.Information);
            Shutdown();
            return;
        }

        base.OnStartup(e);
    }

    protected override void OnExit(ExitEventArgs e)
    {
        _singleInstance?.ReleaseMutex();
        _singleInstance?.Dispose();
        base.OnExit(e);
    }
}
