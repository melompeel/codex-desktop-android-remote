# Android 客户端

原生 Kotlin + Jetpack Compose 客户端，最低 Android 8.0（API 26）。网络层使用 OkHttp，凭据使用 Android Keystore 加密。

## 安装

当前 Debug APK：

```text
app\build\outputs\apk\debug\app-debug.apk
```

连接手机并启用 USB 调试后可以运行：

```powershell
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

也可以把 APK 发送到手机后手动安装。它是 Debug 签名包，只适合当前内部测试，不适合应用商店发布。

## 配对

1. 电脑运行 Windows 包中的 `CodexRemoteManager.exe`。
2. 手机与电脑连接同一个可信 Wi-Fi，或登录到同一 Tailscale tailnet。
3. 在 App 中填写管理器显示的完整连接地址；使用 Tailscale 时也可填写电脑的 `100.64.0.0/10` 地址、MagicDNS 单标签主机名或 `*.ts.net` 名称。
4. 输入管理器显示的六位配对码，完成配对。
5. 允许通知权限，并允许应用在后台持续运行。

App 会拒绝公网 IP 和公网域名。电脑改换网络或 IP 后，可从左侧菜单的“Codex 终端”编辑已保存终端并沿用原授权。

左侧“连接路由”开关按当前终端独立保存。开启时通过 Android 系统 VPN/代理；关闭时该终端绕过系统 HTTP 代理和 VPN，并绑定非 VPN 的 Wi-Fi 或有线网络。App 不会根据 IP 自动更改这个选项；使用 Tailscale 地址时通常需要保持开启。

首次配对可以填写任意终端名称。配对成功后，从左侧菜单进入“Codex 终端”，可以继续添加其他 Codex Desktop 电脑；每台新终端都需要输入它当前的六位配对码，并保存独立加密令牌。切换已保存终端无需验证码；点击编辑按钮修改原终端的名称或 IP 也不需要重新配对。

## 使用

- 任务列表中的“桌面已打开”表示当前 Desktop owner 可接受远程操作。
- “历史”任务可以看已有记录，但发送、停止和推送按钮会锁定。
- 任务详情采用连续对话布局：用户消息靠右，Codex 回复按 Markdown 显示，代码、列表、表格和链接不再显示为原始标记；宽表格可横向滑动。
- 每轮默认只显示用户输入和最后一组 Codex 回复；分析、状态、命令和“正在编辑文件”合并为一条“用时”记录，点击后再展开。
- 首次连接会先显示 Bridge/Codex Desktop 的真实连接状态，再加载任务列表；任务较多时显示加载动画，超时后保持连接状态并自动重试，不再直接展示原始 `timeout`。
- Codex 在桌面任务中展示的本地图片和手机发出的图片都会同步到对话内，点击图片可全屏查看。
- 回复中明确引用的电脑本地文件可点击下载，并交给 Android 系统查看器打开。
- 附件栏的电脑按钮只浏览当前任务工作目录；选中后复制成受控临时附件，不开放整台电脑。
- 输入框固定在底部；附件和模型位于输入框上方，补充/排队方式与发送按钮位于输入框下方。停留在最新位置时会跟随流式输出，阅读旧消息时可点向下按钮回到最新。
- 在会话详情中使用 Android 系统返回键或返回手势，会先回到任务列表，不会直接退出 App。
- 发送框会根据桌面状态自动 start turn 或 steer 当前活动轮次。
- 审批页提供“仅本次允许”“拒绝”“取消”，不提供整场会话永久允许。
- “待处理”同时显示权限审批和已完成待查看的任务，整张卡片均可点击；查看结果后自动清除完成提醒。
- 打开模型选择器时会向电脑重新读取可用模型和推理强度，不使用 App 内置模型名单；选择菜单与字段等宽、限高并可滚动。
- `request_user_input` 的所有问题会在同一个可滚动对话框内回答。
- `isSecret` 问题不会通过当前 HTTP 局域网链路发送，必须回桌面处理。
- “请求推送”必须再次确认，之后只是把受控指令发给 Codex；Git 仍由 Codex 检查并执行。
- 语音输入直接点击手机输入法的麦克风按钮，不需要 App 自己录音。
- 左侧菜单“检查更新”会读取 GitHub Releases；下载由 Android 系统接管，关闭弹窗或切到后台后仍会继续，并在状态栏显示进度。
- 下载完成通知可直接点击安装；App 重启后也会恢复正在下载或等待安装的更新。

## 后台与通知

配对成功后才启动前台服务。服务使用 Android `specialUse` 类型保持可信 Wi-Fi 或 Tailscale WebSocket，并显示常驻连接通知；点击审批或回答通知会直接定位到对应待处理项，点击完成通知会直接打开任务结果。断开配对会停止服务。

App 退到后台后，WebSocket 仍是实时主通道。活动任务期间还会每 15 秒补偿刷新，并仅在任务运行期间保持 CPU 可执行；任务结束后释放唤醒锁并降到 2 分钟刷新一次，以兼顾完成提醒和耗电。

不同手机厂商仍可能额外限制后台网络。若收不到审批通知，需要在系统电池设置中允许该 App 后台运行。

## 构建

需要 JDK 17、Android SDK 35：

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat testDebugUnitTest assembleDebug

# 已启动模拟器或连接测试手机时，可再运行 Compose 交互测试
.\gradlew.bat connectedDebugAndroidTest
```

Windows 对中文长路径处理异常时，可以在仓库根目录临时映射盘符后构建。`R:` 只是当前仓库的临时别名，不会复制文件；它的容量会和 C 盘相同，并会在命令结束后移除：

```powershell
$repo = (Get-Location).Path
subst R: $repo
try {
    Push-Location R:\android
    .\gradlew.bat testDebugUnitTest assembleDebug
} finally {
    Pop-Location
    subst R: /d
}
```
