# AndPan

[English](#english) | [中文说明](#中文说明)

---

## 中文说明

AndPan 可以将一台旧 Android 手机（例如坚果 Pro / Android 7.1+）变成 Mac 桌面前的硬件级语音听写与状态控制台。

无需在两个 App 之间来回切换：AndPan 手机端内置直连语音传输，Mac 端集成托管语音接收服务；日常使用只需手机和 Mac 连接在同一 Wi-Fi 下。

### 核心特性

1. **双模式语音控制**：
   - **按住说话**：按住时取消静音并模拟 Mac `Fn` 键按下，松手立即释放并恢复静音，完美契合 macOS 自带听写或长按语音工具。
   - **持续听写（常开收音）**：只打开无线麦克风传输和 Mac 输入静音开关，**不占用或按住 Fn 键**，彻底避免干扰 Mac 上的其他快捷键或程序切换。
2. **实时声音指示**：36 格动态自绘电平条，平滑显示 Mac 实际采集到的输入音量，静音或断开时真实熄灭。
3. **Mac 桌面监控与安全发送**：
   - 实时显示 Mac CPU、内存占用及高消耗进程排行。
   - 显示当前焦点应用，点击「发送 ↑」向目标程序发送回车；若发送时应用已发生切换则自动拦截，防止误发。
4. **局域网无线自发现与无感升级**：
   - 首次通过 USB 安装并自动配对信任证书后即可拔线；后续同一 Wi-Fi 下 UDP 自动发现并建立 TLS 加密通信。
   - 手机端支持通过 Wi-Fi 直接拉取 Mac 上的新版 APK 覆盖升级。
5. **一体化桌面管理**：
   - Mac 端管理面板支持一键复制局域网 IP、状态监测（手机连接、语音通道、BlackHole 输入与辅助功能权限）、服务启停与系统设置快捷跳转。
   - 附带 macOS 菜单栏小图标，关闭主窗口后服务继续在后台与菜单栏运行。

### 快速开始

1. **启动 Mac 端**：
   在 Mac 上运行 `AndPan.app`（或执行 `./mac/start.command`）。服务会自动启动并监听本地及局域网端口（默认 HTTP `43125`、TLS `43126`、内置语音 `8554`）。
2. **配置虚拟音频**：
   建议将 Mac 系统默认输入设备设为 **BlackHole 2ch**。macOS 首次运行需要允许 AndPan 的麦克风权限与设备控制（辅助功能）权限。
3. **安装手机端**：
   首次使用将手机通过 USB 连接 Mac 并开启 USB 调试，在 Mac 面板上点击「安装 / 更新手机端」（或终端执行 `./mac/install.command`）。配对完成后即可拔掉 USB 线。
4. **日常使用**：
   手机和 Mac 处于同一 Wi-Fi 下即可自动连接，顶部显示 `Mac 已连接` 与 `语音 · 已直连`。按住说话听写，或开启持续听写后随时点击发送。

### 构建工程

- 构建 Android APK：`./android/build.sh`（产物位于 `.build/android/andpan.apk`）
- 构建 Mac 桌面端：`./mac/build-app.sh`（生成 `AndPan.app` 并链接至 `/Applications`）
- 服务端回归测试：`node --test tests/server.test.mjs`

---

## English

AndPan turns a spare Android phone (e.g. Android 7.1+) into a dedicated voice dictation and hardware-like status companion panel for your Mac.

No app-switching required: the Android client streams low-latency PCM audio directly to Mac's integrated receiver over local Wi-Fi.

### Features

1. **Dual-Mode Voice Control**:
   - **Push-to-Talk**: Unmutes Mac input and simulates holding the `Fn` key; releases and mutes immediately upon release. Designed for macOS system dictation and voice input.
   - **Continuous Mode**: Keeps the microphone stream and Mac input unmuted **without pressing or holding the Fn key**, eliminating any modifier-key interference with other desktop apps and shortcuts.
2. **Real-time Audio Meter**: 36-segment responsive level bar displaying actual Mac input volume, returning to true silence when muted.
3. **Mac System Monitor & Safe Dispatch**:
   - Real-time CPU usage, memory pressure, and top resource-consuming processes.
   - Displays the current foreground Mac application. Pressing "Send ↑" delivers a Return keystroke directly to the active application, guarded against accidental app switches.
4. **LAN Auto-Discovery & Wireless OTA Update**:
   - Zero-config UDP discovery on local Wi-Fi with TLS certificate pinning and shared token authentication after initial pairing.
   - Supports downloading and installing updated APKs directly from the Mac over Wi-Fi without reconnecting USB.
5. **Integrated macOS Companion App**:
   - Dark Aqua visual theme with one-click IP copy, health indicators, service controls, and deep links to macOS Privacy / Sound settings.
   - Persistent menu bar icon allows the service to run quietly in the background when the main window is closed.

### Getting Started

1. **Launch Mac Service**:
   Open `AndPan.app` or run `./mac/start.command`. The server starts both the control API and the bundled audio daemon.
2. **Audio Setup**:
   Set the Mac default input device to **BlackHole 2ch**. Grant Accessibility and Microphone permissions to AndPan when prompted.
3. **Install Android App**:
   For initial setup, connect your Android device via USB with ADB debugging enabled. Click "Install / Update Phone App" in AndPan Mac, or run `./mac/install.command`. Disconnect USB once paired.
4. **Daily Workflow**:
   Ensure both devices are on the same Wi-Fi. AndPan connects automatically. Use Push-to-Talk or Continuous Dictation, monitor system load, and dispatch Enter to your focused editor.

### Build & Test

- Build APK: `./android/build.sh` -> `.build/android/andpan.apk`
- Build Mac App: `./mac/build-app.sh` -> `AndPan.app`
- Test Suite: `node --test tests/server.test.mjs`

1. 在 Mac 的「应用程序」中打开 **AndPan**。窗口会自动启动主服务和内置的 MicYou 语音接收服务，无需单独打开 MicYou。macOS 如请求设备控制或麦克风权限，请允许 AndPan 使用。设备控制位于「系统设置 → 隐私与安全 → 设备控制和数据访问」，旧版 macOS 可能称为「辅助功能」。项目更新后若开关已开启但仍提示未授权，请移除列表中的旧 **AndPan Mac**，再添加本项目目录里的 `AndPan Mac.app` 并启用。
2. 首次安装或更新手机端时，用 USB 连接 Android，打开 USB 调试并允许电脑连接，再在 Mac 窗口点击「安装 / 更新手机端」。安装和设置完成后可以拔掉 USB。
3. 手机、Mac 使用同一个 Wi-Fi。手机会自动查找已配对的 Mac；也可在手机 AndPan 中点「设置 → 自动查找」，或手动填写 Mac 窗口显示的地址并测试保存。
4. 在手机允许 AndPan 使用麦克风权限。手机端会自动直连 Mac 的语音接收端口（默认 `8554`），顶部状态栏显示「语音 · 已直连」，无需再切到另一个手机 App。
5. 将 Mac 默认输入设备设为 **BlackHole 2ch**，并将电脑上的语音输入设置为按住 Fn 听写。返回手机面板，确认输入设备显示正常。
6. 在电脑上切到要输入的应用并选中输入框。按住手机上的「按住说话」，松手结束；也可以使用「持续听写」开关。声音条显示 Mac 接收到的实际输入电平，静音时不会显示声音。
7. 等语音识别完成、在电脑上确认文字后，按手机「发送」。面板会向显示的当前应用发一个回车：它是发送还是换行，取决于目标应用。发送时如果电脑已切换到其他应用，操作会被拒绝，请确认目标后重试。

关闭或退出 Mac 的 AndPan 窗口后服务继续运行；需要断开时在窗口点击「停止服务」，内置语音接收服务也会一并停止。Mac 重启后，从「应用程序」再次打开 AndPan 即可。手机面板顶部的「退出」可关闭手机界面。

「应用程序」中的 AndPan 指向本项目目录，请保留本目录；移动项目后，需要重新运行 `mac/build-app.sh` 更新应用入口。

## 声音和状态

- 声音条只在听写期间读取 Mac 默认输入设备的实时电平，显示的是测量值，不保存录音。若显示「无法读取音量」，请检查 AndPan 的麦克风权限和输入设备。
- 手机平时只保持轻量的控制心跳连接，仅在按住「按住说话」或开启「持续听写」时才打开手机麦克风采集并发送 PCM 音频；松手或关闭后立即停止采音，并将 Mac 端输入静音。
- 按住说话或开启持续听写时，面板解除 Mac 输入静音并模拟按住 Fn；松手或关闭持续听写时释放 Fn 并静音。手机离开面板、服务正常退出，或听写时断线约 6 秒后，也会释放 Fn 并静音。
- 面板显示 Mac 的 CPU 使用率、由系统可用内存估算的内存占用，以及当前 CPU 占用最高的三个进程。进程 CPU 按单核 100% 计算，因此可能超过 100%。
- 停止 AndPan 会一并停止它内置启动的语音接收服务，Mac 输入保持静音；如需恢复输入，可重新打开 AndPan 使用听写，或在 Mac 声音设置中调整。

## 使用范围

- 手机和 Mac 需要处于可互通的同一局域网，Mac 不能睡眠；目前不提供外网远程连接。
- 发送目标是 Mac **当前活动应用**，并非绑定特定会话；面板不能区分同一应用的不同窗口或输入框。发送结果不明确时，先检查电脑，避免重复发送。
- 无线控制使用本机生成的密钥和加密连接，不经过第三方服务器。

## 构建与检查

`./android/build.sh` 构建 `.build/android/andpan.apk`；默认使用 `~/.cache/andpan-toolchain` 中的 JDK 和 Android SDK。`./mac/build-app.sh` 构建 Mac 应用并在「应用程序」创建入口。`node --test tests/server.test.mjs` 检查发送去重、目标切换、无线连接、麦克风状态和失联静音。
