#!/usr/bin/env python3
"""生成可分发的源码包和校验值，严格排除本机缓存、签名及用户文件。"""
from pathlib import Path
import hashlib
import os
import zipfile
import sys
import re

root = Path(__file__).resolve().parent.parent
if not (root / "third-party/rust").is_dir():
    raise SystemExit("先运行 bash scripts/dev.sh vendor-dependencies")
dist = root / "dist"
dist.mkdir(exist_ok=True)
version = re.search(r"versionName '([^']+)'", (root / "app/build.gradle").read_text()).group(1).removesuffix("-dev")
destination = dist / f"jianxue-android-{version}-source.zip"
stage = dist / f"jianxue-android-{version}-source.zip.part"
staging = "--stage" in sys.argv
finishing = "--finish" in sys.argv
if finishing:
    if not stage.is_file():
        raise SystemExit("缺少源码暂存包")
    with zipfile.ZipFile(stage) as previous:
        for name in previous.namelist():
            relative = name.removeprefix("jianxue-android/")
            if relative.startswith(("app/src/", "native/src/", "scripts/", "patches/")):
                actual = root / relative
                assert actual.is_file() and actual.read_bytes() == previous.read(name), f"源码在打包后发生变化：{relative}"
    with zipfile.ZipFile(stage, "a", zipfile.ZIP_DEFLATED, compresslevel=6, strict_timestamps=False) as output:
        output.write(root / "docs/VALIDATION.md", "jianxue-android/docs/VALIDATION.md")
    stage.replace(destination)
skip_dirs = {".git", ".gradle", ".idea", "dist", "node_modules", "__pycache__", ".codex", ".agents", ".aws"}
skip_files = {"local.properties", ".DS_Store"}
included_roots = ["app", "native", "vendor", "scripts", "docs", "patches", "gradle", ".cargo", ".github", "third-party"]
included_files = ["README.md", "CONTRIBUTING.md", "CHANGELOG.md", "LICENSE", "NOTICE.md", ".gitignore", "build.gradle", "settings.gradle", "gradle.properties", "gradlew", "gradlew.bat", "rust-toolchain.toml"]
if not finishing:
  with zipfile.ZipFile(stage if staging else destination, "w", zipfile.ZIP_DEFLATED, compresslevel=6, strict_timestamps=False) as output:
    def add(path):
        name = path.relative_to(root).as_posix()
        if staging and name == "docs/VALIDATION.md":
            return
        output.write(path, "jianxue-android/" + name)

    for name in included_files:
        path = root / name
        if not path.is_file():
            raise SystemExit(f"缺少源码文件：{name}")
        add(path)
    for name in included_roots:
        for directory, subdirs, files in os.walk(root / name):
            relative = Path(directory).relative_to(root)
            if name != "third-party":
                subdirs[:] = [item for item in subdirs if item not in skip_dirs
                              and not (relative in {Path("app"), Path("native"), Path("vendor/qingjian")} and item in {"build", "target"})
                              and not (relative == Path("vendor/qingjian") and item == "data")
                              and not (relative == Path("app/src/main") and item == "jniLibs")]
            for filename in sorted(files):
                if relative == Path(".cargo") and filename == "config.toml":
                    continue
                if name != "third-party" and (filename in skip_files or filename.endswith((".jks", ".keystore", ".apk", ".pyc"))):
                    continue
                add(Path(directory) / filename)
    output.writestr("jianxue-android/.cargo/config.toml", (root / ".cargo/config.toml").read_text()
                    + '\n[source.crates-io]\nreplace-with = "vendored-sources"\n\n[source.vendored-sources]\ndirectory = "third-party/rust"\n')
    output.writestr("jianxue-android/SOURCE-BUNDLE.txt", "此包包含锁文件中 Rust 依赖的原源码和许可证。编译需另外准备 JDK、Rust 工具链、Android SDK/NDK、Gradle 和固定版本数据包，见 docs/BUILD.md。\n")
if staging:
    print(f"源码暂存包已准备：{stage.stat().st_size / 1024 ** 2:.1f} MiB；验证结束后运行 --finish 写入最终记录。")
    raise SystemExit(0)
rows = []
for path in sorted(dist.iterdir()):
    if path.is_file() and path.suffix in {".apk", ".zip"}:
        with path.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        rows.append(f"{digest}  {path.name}")
(dist / "SHA256SUMS").write_text("\n".join(rows) + "\n")
print(f"源码：{destination.name} ({destination.stat().st_size / 1024 ** 2:.1f} MiB)")
print("\n".join(rows))
