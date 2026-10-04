# 构建与开发

已验证工具链：Linux x86_64 / WSL2、JDK 17、Rust 1.96.0、Gradle 8.11.1、Android Gradle Plugin 8.9.2、SDK 35、NDK 27.2.12479018。Python 3.11+ 用于源码打包；需要 curl、tar、unzip、CMake、C/C++ 编译器、pkg-config、patch、ripgrep。Ubuntu 可安装 `build-essential cmake pkg-config curl unzip python3 patch ripgrep`。

在项目根目录运行：

```bash
bash scripts/fetch-upstream.sh
bash scripts/dev.sh setup
bash scripts/dev.sh data
bash scripts/dev.sh vendor-dependencies
bash scripts/dev.sh check
bash scripts/dev.sh apk
bash scripts/dev.sh jvm
```

`setup` 默认将工具下载到 `/tmp/jianxue-tools`，不改系统 Java 或 Rust。可设置 `JIANXUE_TOOLS=/你的可写目录` 持久保存缓存。Android SDK 安装需要接受 Google 的 SDK 许可。首次下载和编译需要联网及数 GB 空间。

`fetch-upstream.sh` 固定源码版本并校验归档，首次下载后应用 `patches/0001-android-tls.patch`。源码包已含修改后的上游，存在该目录时保留现有内容。正式词库与模型由上游固定的 `tools/release/data.lock` 下载、校验，禁止用样例词库构建 APK。

`apk` 顺序构建两种 ABI 的 Rust JNI 库，打包 APK，运行 Java 单元测试与 lint；输出 `dist/jianxue-debug.apk`。JNI 库按 16 KB 页对齐，Gradle 使用未压缩原生库打包。只修改 Java 时，可运行 `bash scripts/dev.sh android`。JNI 代码变更后必须先重新构建原生库。

也可在 Android Studio 中打开本目录。先按上述步骤准备资源与 JNI，配置 JDK 17 和 SDK/NDK，再用 Gradle Wrapper 执行 `./gradlew :app:assembleDebug`。开发脚本当前为 Linux / WSL 设计；Windows 原生交叉编译脚本尚未提供。

## 设备测试

本机准备模拟器：

```bash
bash scripts/dev.sh emulator-setup
bash scripts/dev.sh emulator
```

它创建独立测试 AVD，启动无窗口 Android 11 模拟器，安装本项目及测试包，启用输入法后通过 `am instrument` 验证真实 `InputConnection`。软件加速启动较慢；有 KVM 的环境可调整模拟器参数。使用独立模拟器是为了避免替换个人手机的默认输入法。

默认运行会清空这个测试 AVD，并在结束时关闭它。调试时可设置 `JIANXUE_KEEP_EMULATOR=1` 保留模拟器；修复并重建 APK 后，用 `JIANXUE_REUSE_EMULATOR=1 bash scripts/dev.sh emulator` 复测。复用模式核对 AVD 名称为 `jianxue-test`，不会接受个人手机或其他模拟器。

在自己选择的设备上也可以使用 `./gradlew :app:connectedDebugAndroidTest`；先安装并在系统中启用本输入法。测试使用应用内的试输入框，验证拼音上屏、删空拼音、密码直输、系统禁止学习标志。云接口需要自己的服务与密钥，没有随仓库提供凭据。

## 源码分发

```bash
bash scripts/dev.sh vendor-dependencies
python3 scripts/package-source.py
```

打包脚本排除构建缓存、Git 元数据、签名密钥、个人设置、已下载的产品包和 APK，保留 Android/Rust 代码、锁文件、完整上游源码、资源来源文档、TLS 补丁、GPL 许可、构建脚本与 Rust 第三方源码。源码包内提供 Cargo 离线依赖配置；正式二进制数据通过固定校验值重新下载。

开发 APK 使用调试签名，供安装测试。正式发布需要维护者自行管理发布签名、版本号及升级策略，不要提交签名密钥。
