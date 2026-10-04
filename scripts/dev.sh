#!/usr/bin/env bash
set -euo pipefail
project_dir="${JIANXUE_PROJECT:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$project_dir"
tool_dir="${JIANXUE_TOOLS:-/tmp/jianxue-tools}"
if [[ "${BASH_SOURCE[0]}" == scripts/dev.sh || "${BASH_SOURCE[0]}" == "$project_dir/scripts/dev.sh" ]]; then
    mkdir -p "$tool_dir"
    script_snapshot="$(mktemp "$tool_dir/dev-XXXXXX.sh")"
    cp "$project_dir/scripts/dev.sh" "$script_snapshot"
    export JIANXUE_PROJECT="$project_dir"
    exec bash "$script_snapshot" "$@"
fi
export JAVA_HOME="$tool_dir/jdk"
export ANDROID_HOME="$tool_dir/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="$tool_dir/android-user"
export JIANXUE_DEBUG_KEYSTORE="$tool_dir/jianxue-debug.keystore"
export CARGO_HOME="$tool_dir/cargo"
export RUSTUP_HOME="$tool_dir/rustup"
export GRADLE_USER_HOME="$tool_dir/gradle-cache"
export PATH="$JAVA_HOME/bin:$CARGO_HOME/bin:$tool_dir/gradle/bin:$PATH"
[[ -z "${HTTP_PROXY:-}" ]] && unset HTTP_PROXY
[[ -z "${HTTPS_PROXY:-}" ]] && unset HTTPS_PROXY
case "${1:-}" in
    apk|android|check|jvm|emulator|vendor-dependencies)
        mkdir -p "$tool_dir"
        exec > >(tee "$tool_dir/run-${1}.log") 2>&1
        ;;
esac
if [[ "${1:-}" == apk || "${1:-}" == android ]]; then
    if [[ ! -f "$JIANXUE_DEBUG_KEYSTORE" ]]; then
        keytool -genkeypair -keystore "$JIANXUE_DEBUG_KEYSTORE" -storepass android -keypass android \
            -alias androiddebugkey -dname 'CN=Android Debug,O=Android,C=US' -keyalg RSA -keysize 2048 -validity 10000
    fi
fi

case "${1:-help}" in
  api)
    shift
    rg -n 'pub (fn|struct|enum|[a-z_]+:)|^impl |^\[|^license|^version' "$@"
    ;;
  status)
    git status --short
    du -sh vendor/qingjian/data/generated vendor/qingjian/data/models native/target 2>/dev/null || true
    ls -l /dev/kvm "$ANDROID_HOME/platform-tools/adb" 2>/dev/null || true
    "$ANDROID_HOME/platform-tools/adb" devices
    ;;
  diagnostics)
    for file in "$tool_dir"/native-*.log "$tool_dir/emulator.log"; do
      [[ -f "$file" ]] && tail -25 "$file"
    done
    for path in app/src/main/jniLibs app/build/outputs app/build/reports dist "$tool_dir/android-sdk/emulator" "$tool_dir/avd"; do
      [[ -e "$path" ]] && ls -lh "$path"
    done
    ;;
  emulator-status)
    "$ANDROID_HOME/platform-tools/adb" devices
    timeout 8 "$ANDROID_HOME/platform-tools/adb" -s emulator-5554 shell getprop sys.boot_completed || true
    tail -10 "$tool_dir/emulator.log"
    ;;
  device-state)
    timeout 15 "$ANDROID_HOME/platform-tools/adb" -s emulator-5554 shell ime list -a -s
    timeout 15 "$ANDROID_HOME/platform-tools/adb" -s emulator-5554 shell pm list packages org.jianxue.ime
    ;;
  device-registration)
    mkdir -p dist
    timeout 30 "$ANDROID_HOME/platform-tools/adb" -s emulator-5554 shell dumpsys package org.jianxue.ime > dist/android-registration.txt
    timeout 30 "$ANDROID_HOME/platform-tools/adb" -s emulator-5554 logcat -d -s InputMethodManagerService PackageManager > dist/android-ime-log.txt
    cat dist/android-ime-log.txt
    ;;
  search)
    shift
    rg "$@"
    ;;
  inspect)
    shift
    for file in "$@"; do
      case "$file" in /*|*..*) echo '只接受项目内相对路径' >&2; exit 1;; esac
      if [[ -d "$file" ]]; then rg --files "$file"; elif [[ -f "$file" ]]; then cat "$file"; else echo "缺少：$file"; fi
    done
    ;;
  setup)
    mkdir -p "$tool_dir" "$ANDROID_HOME/cmdline-tools"
    if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
      curl -fL --retry 3 'https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse' -o "$tool_dir/jdk.tar.gz"
      mkdir -p "$JAVA_HOME"
      tar -xzf "$tool_dir/jdk.tar.gz" --strip-components=1 -C "$JAVA_HOME"
    fi
    if [[ ! -x "$CARGO_HOME/bin/rustup" ]]; then
      curl -fL --retry 3 https://sh.rustup.rs -o "$tool_dir/rustup-init.sh"
      sh "$tool_dir/rustup-init.sh" -y --profile minimal --default-toolchain 1.96.0 --no-modify-path
    fi
    rustup target add aarch64-linux-android x86_64-linux-android
    rustup component add rustfmt clippy
    if [[ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]]; then
      curl -fL --retry 3 https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -o "$tool_dir/android-tools.zip"
      python3 -c 'import zipfile,sys; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])' "$tool_dir/android-tools.zip" "$ANDROID_HOME/cmdline-tools"
      mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
      chmod +x "$ANDROID_HOME/cmdline-tools/latest/bin/"*
    fi
    set +o pipefail
    yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null
    set -o pipefail
    "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" 'platforms;android-35' 'build-tools;35.0.0' 'platform-tools' 'ndk;27.2.12479018'
    if [[ ! -x "$tool_dir/gradle/bin/gradle" ]]; then
      curl -fL --retry 3 https://services.gradle.org/distributions/gradle-8.11.1-bin.zip -o "$tool_dir/gradle.zip"
      curl -fL --retry 3 https://services.gradle.org/distributions/gradle-8.11.1-bin.zip.sha256 -o "$tool_dir/gradle.zip.sha256"
      python3 -c 'import hashlib,sys,zipfile; assert hashlib.sha256(open(sys.argv[1],"rb").read()).hexdigest()==open(sys.argv[1]+".sha256").read().strip(); zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])' "$tool_dir/gradle.zip" "$tool_dir"
      mv "$tool_dir/gradle-8.11.1" "$tool_dir/gradle"
      chmod +x "$tool_dir/gradle/bin/gradle"
    fi
    java -version
    cargo --version
    ;;
  test)
    cargo test --manifest-path native/Cargo.toml
    ;;
  data)
    bash vendor/qingjian/tools/release/data-fetch.sh
    ;;
  native)
    ndk_bin="$ANDROID_HOME/ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64/bin"
    export PATH="$ndk_bin:$PATH"
    export CC_aarch64_linux_android="$ndk_bin/aarch64-linux-android26-clang"
    export AR_aarch64_linux_android="$ndk_bin/llvm-ar"
    export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CC_aarch64_linux_android"
    export CC_x86_64_linux_android="$ndk_bin/x86_64-linux-android26-clang"
    export AR_x86_64_linux_android="$ndk_bin/llvm-ar"
    export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$CC_x86_64_linux_android"
    for target in aarch64-linux-android x86_64-linux-android; do
      native_log="$tool_dir/native-$target.log"
      if ! cargo build --manifest-path native/Cargo.toml --release --target "$target" >"$native_log" 2>&1; then tail -80 "$native_log"; exit 1; fi
      abi=arm64-v8a
      [[ "$target" == x86_64-linux-android ]] && abi=x86_64
      mkdir -p "app/src/main/jniLibs/$abi"
      cp "native/target/$target/release/libjianxue.so" "app/src/main/jniLibs/$abi/"
    done
    ;;
  apk)
    bash scripts/dev.sh native
    gradle --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
    mkdir -p dist
    cp app/build/outputs/apk/debug/app-debug.apk dist/jianxue-debug.apk.next
    mv -f dist/jianxue-debug.apk.next dist/jianxue-debug.apk
    ;;
  android)
    gradle --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
    mkdir -p dist
    cp app/build/outputs/apk/debug/app-debug.apk dist/jianxue-debug.apk.next
    mv -f dist/jianxue-debug.apk.next dist/jianxue-debug.apk
    ;;
  jvm)
    cargo build --manifest-path native/Cargo.toml --lib
    mkdir -p "$tool_dir/jvm-smoke"
    javac -d "$tool_dir/jvm-smoke" app/src/main/java/org/jianxue/ime/engine/NativeEngine.java scripts/JniSmoke.java
    java -Djava.library.path="$project_dir/native/target/debug" -cp "$tool_dir/jvm-smoke" JniSmoke "$project_dir/app/build/generated/assets" "$tool_dir/jvm-user"
    ;;
  emulator-setup)
    export ANDROID_USER_HOME="$tool_dir/android-user"
    export ANDROID_AVD_HOME="$tool_dir/avd"
    "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" 'emulator' 'system-images;android-30;default;x86_64'
    mkdir -p "$ANDROID_AVD_HOME"
    printf 'no\n' | "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd -f -n jianxue-test -k 'system-images;android-30;default;x86_64' -d pixel_4
    ;;
  emulator)
    [[ -f dist/jianxue-debug.apk && -f app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk ]] || {
      echo '先完成 APK 与 androidTest 构建，再启动设备测试。' >&2; exit 1;
    }
    export ANDROID_USER_HOME="$tool_dir/android-user"
    export ANDROID_AVD_HOME="$tool_dir/avd"
    export ANDROID_EMULATOR_HOME="$tool_dir/android-user"
    adb="$ANDROID_HOME/platform-tools/adb"
    emulator_pid=
    if [[ "${JIANXUE_REUSE_EMULATOR:-0}" == 1 ]]; then
      [[ "$("$adb" -s emulator-5554 emu avd name | head -1 | tr -d '\r')" == jianxue-test ]] || {
        echo '只能复用本项目的 jianxue-test 模拟器。' >&2; exit 1;
      }
    else
      "$ANDROID_HOME/emulator/emulator" -avd jianxue-test -wipe-data -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -accel off -no-snapshot -memory 4096 -cores 2 -port 5554 >"$tool_dir/emulator.log" 2>&1 &
      emulator_pid=$!
    fi
    trap 'status=$?; if [[ "$status" != 0 ]]; then mkdir -p dist; timeout 20 "$adb" -s emulator-5554 logcat -d -t 1000 > dist/android-logcat.txt 2>/dev/null || true; fi; if [[ -n "$emulator_pid" && "${JIANXUE_KEEP_EMULATOR:-0}" != 1 ]]; then kill "$emulator_pid" 2>/dev/null || true; fi' EXIT
    for attempt in $(seq 1 240); do
      if [[ -n "$emulator_pid" ]] && ! kill -0 "$emulator_pid" 2>/dev/null; then tail -60 "$tool_dir/emulator.log"; exit 1; fi
      if [[ "$(timeout 8 "$adb" -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; then break; fi
      sleep 2
    done
    for attempt in $(seq 1 120); do
      if timeout 8 "$adb" -s emulator-5554 shell cmd package list packages android 2>/dev/null | rg -q '^package:android'; then break; fi
      sleep 2
    done
    "$adb" -s emulator-5554 shell input keyevent 82
    "$adb" -s emulator-5554 shell svc power stayon true
    "$adb" -s emulator-5554 shell settings put system screen_off_timeout 2147483647
    "$adb" -s emulator-5554 shell wm dismiss-keyguard
    # 测试 AVD 只包含本任务安装的数据；移除旧调试签名，避免不同开发密钥阻止更新。
    if [[ "${JIANXUE_REUSE_EMULATOR:-0}" != 1 ]]; then
      timeout 60 "$adb" -s emulator-5554 uninstall org.jianxue.ime.test || true
      timeout 60 "$adb" -s emulator-5554 uninstall org.jianxue.ime || true
    fi
    timeout 300 "$adb" -s emulator-5554 install --no-streaming -r dist/jianxue-debug.apk
    timeout 120 "$adb" -s emulator-5554 install --no-streaming -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
    for attempt in $(seq 1 60); do
      ime_list="$(timeout 15 "$adb" -s emulator-5554 shell ime list -a -s | tr -d '\r')"
      if [[ "$ime_list" == *org.jianxue.ime* ]]; then break; fi
      sleep 2
    done
    [[ "$ime_list" == *org.jianxue.ime* ]] || { echo 'Android 未注册简学输入法服务。' >&2; exit 1; }
    "$adb" -s emulator-5554 shell ime enable org.jianxue.ime/.JianxueService
    "$adb" -s emulator-5554 shell ime set org.jianxue.ime/.JianxueService
    "$adb" -s emulator-5554 shell settings put secure show_ime_with_hard_keyboard 1
    mkdir -p dist
    "$adb" -s emulator-5554 shell am instrument -w -r org.jianxue.ime.test/androidx.test.runner.AndroidJUnitRunner | tee dist/instrumentation.txt
    if ! rg -q 'OK \([0-9]+ tests?\)' dist/instrumentation.txt; then
      "$adb" -s emulator-5554 logcat -d -t 300 > dist/android-logcat.txt
      exit 1
    fi
    mkdir -p dist
    "$adb" -s emulator-5554 shell am start -n org.jianxue.ime/.settings.SettingsActivity
    sleep 2
    "$adb" -s emulator-5554 shell uiautomator dump /sdcard/jianxue-ui.xml
    "$adb" -s emulator-5554 pull /sdcard/jianxue-ui.xml dist/android-ui.xml
    trial_point="$(python3 -c 'import sys,re,xml.etree.ElementTree as E; node=next(n for n in E.parse(sys.argv[1]).iter("node") if n.attrib.get("class")=="android.widget.EditText"); x1,y1,x2,y2=map(int,re.findall(r"\d+",node.attrib["bounds"])); print((x1+x2)//2,(y1+y2)//2)' dist/android-ui.xml)"
    read -r trial_x trial_y <<< "$trial_point"
    "$adb" -s emulator-5554 shell input tap "$trial_x" "$trial_y"
    "$adb" -s emulator-5554 shell input text nihao
    sleep 3
    "$adb" -s emulator-5554 exec-out screencap -p > dist/android-emulator.png
    ;;
  verify)
    python3 scripts/verify-artifacts.py
    "$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose --print-certs dist/jianxue-debug.apk > dist/apk-signature.txt
    "$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk > "$tool_dir/test-apk-signature.txt"
    python3 -c 'import re,sys; a=re.search(r"certificate SHA-256 digest: (\w+)",open(sys.argv[1]).read()).group(1); b=re.search(r"certificate SHA-256 digest: (\w+)",open(sys.argv[2]).read()).group(1); assert a==b,"测试签名不一致"; print("APK 签名校验通过，应用与测试包证书一致。")' dist/apk-signature.txt "$tool_dir/test-apk-signature.txt"
    ;;
  format)
    cargo fmt --manifest-path native/Cargo.toml
    ;;
  vendor-dependencies)
    cargo vendor --locked --manifest-path native/Cargo.toml third-party/rust > "$tool_dir/vendor-config.toml"
    python3 scripts/check-vendor.py
    ;;
  vendor-check)
    python3 scripts/check-vendor.py
    ;;
  wrapper)
    gradle --no-daemon wrapper --gradle-version 8.11.1 --distribution-type bin
    ;;
  check)
    cargo fmt --manifest-path native/Cargo.toml --check
    cargo clippy --manifest-path native/Cargo.toml --all-targets -- -D warnings
    bash scripts/dev.sh test
    ;;
  *) echo '用法：bash scripts/dev.sh setup|test|native|apk|check|inspect <项目内路径>';;
esac
