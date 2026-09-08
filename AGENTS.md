# 项目构建注意事项

## Android

- Android Gradle Plugin 需要 Java 11 以上。本机默认 `java` 可能仍是 Java 8；构建时优先使用 Android Studio 自带的 `C:\Program Files\Android\Android Studio\jbr\bin\java.exe`。
- 仓库上级路径包含中文和空格。Kotlin 增量缓存如果混用真实路径与 `R:` 路径，可能出现 `different roots`、测试类 `ClassNotFoundException` 等假失败。请在同一个 PowerShell 进程内临时执行 `subst R: <仓库绝对路径>`，从 `R:\android` 构建，并在 `finally` 中执行 `subst R: /D`。
- 如果已经混用过两种路径，先从准备继续使用的同一路径执行一次 `clean`，再运行：`testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug`。
- 如果所有 Android 任务都已执行，但 Gradle 最后因 `FileAlreadyExistsException: ...problems-report.html` 返回失败，这是生成报告冲突。只删除 `android\build\reports\problems\problems-report.html`，再从同一 `R:` 路径重跑原命令，不要修改源码。
- 若报错无法读取 `%LOCALAPPDATA%\Android\Sdk\...\package.xml`，通常是受限执行环境没有 Android SDK 读取权限。应在获得相应权限后重跑同一命令，不要因此修改源码或重新安装 SDK。

## Windows

- 若 `dotnet build` 报错无法访问 `%LOCALAPPDATA%\Microsoft SDKs`，通常同样是受限执行环境权限问题。获得 SDK 读取权限后重跑，先确认它不是 C# 编译错误。
- 若管理器正从 `windows_launcher\bin` 运行，`dotnet build` 会因 `CodexRemoteManager.exe` 被锁定而失败。可先退出管理器，或使用独立 `-o` 目录验证；不要把文件锁定误判为 C# 编译失败。
- 正式 Windows 包使用 `windows_launcher\publish-windows.ps1` 生成；该脚本同时编译并打包最新 `desktop_bridge`，不要只复制旧的 `dist`。

## 产物与 Git

- 构建目录、APK、ZIP 和 `windows_launcher\release` 内容默认不提交 Git；只有用户明确要求发布时才上传对应 Release 资产。
- 构建结束后确认临时 `R:` 映射已移除，避免后续命令再次产生混合根路径缓存。
