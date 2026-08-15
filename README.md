# AndroidCoder

基于 proroot（rootless 容器运行时）+ Debian rootfs 的 Android 编码 Agent 应用。

- 容器内运行完整 Debian（glibc），提供 shell / 文件 / 编译等编码能力
- AI Agent 驱动任务循环：思考 → 工具调用 → 回答，流式输出
- 工具集：read_file / write_file / edit_file / delete_file / list_dir / glob / grep / run_command

## 系统要求

- Android 8.0+ (API 26)
- ARM64 设备（仅支持 arm64-v8a）
- JDK 17 或更高版本
- Android SDK 34
- Android NDK r26

## 编译 APK

### 1. 安装依赖

确保已安装以下组件（可通过 Android Studio SDK Manager 安装）：

- Android SDK Platform 34
- Android SDK Build-Tools 34.x
- Android NDK 26.1.10909125

### 2. 配置 local.properties

在项目根目录创建 `local.properties`，指向本机的 Android SDK 路径：

```properties
sdk.dir=C:\\Program Files\\Android\\Sdk
```

> macOS / Linux 示例：`sdk.dir=/Users/username/Library/Android/sdk`

### 3. 编译 Debug APK

```bash
# Linux / macOS
./gradlew assembleDebug

# Windows
gradlew.bat assembleDebug
```

输出位于 `app/build/outputs/apk/debug/app-debug.apk`。

### 4. 编译 Release APK

Release 构建默认使用 debug 签名配置，可直接安装。若需自有签名，见下方 [签名指南](#签名指南)。

## 技术架构

| 层 | 说明 |
|----|------|
| 容器运行时 | [proroot](https://github.com/coderredlab/proroot)：LD_PRELOAD 实现 rootless 根文件系统重定向 |
| 文件系统 | 预置 Debian rootfs（`assets/rootfs/layer.dat`） |
| PTY 桥接 | C 模块（`cpp/pty.c`）通过 JNI 暴露伪终端接口 |
| AI Agent | 通过 OpenAI / Anthropic 兼容 API 调用大模型，流式 SSE 解析 |

## License

MIT
