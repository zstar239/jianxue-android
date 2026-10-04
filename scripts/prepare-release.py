#!/usr/bin/env python3
"""保存许可、上游修改补丁与构建工具校验值；不读取用户设置或密钥。"""
from pathlib import Path
import difflib
import hashlib
import shutil
import tarfile

root = Path(__file__).resolve().parent.parent
revision = (root / "vendor/UPSTREAM_REVISION").read_text().strip()
shutil.copyfile(root / "vendor/qingjian/LICENSE", root / "LICENSE")
archive = Path("/tmp") / f"jianxue-qingjian-{revision}.tar.gz"
if not archive.exists():
    raise SystemExit("缺少固定版本上游归档；先运行 fetch-upstream.sh")
patches = root / "patches"
patches.mkdir(exist_ok=True)
changed = ["Cargo.toml", "crates/qingjian-predict/Cargo.toml", "crates/qingjian-predict/src/chat_client.rs"]
diff = []
with tarfile.open(archive) as source:
    for name in changed:
        original = source.extractfile(f"qingjian-{revision}/{name}").read().decode().splitlines(keepends=True)
        adapted = (root / "vendor/qingjian" / name).read_text().splitlines(keepends=True)
        diff.extend(difflib.unified_diff(original, adapted, f"a/{name}", f"b/{name}"))
(patches / "0001-android-tls.patch").write_text("".join(diff))
sha = hashlib.file_digest(archive.open("rb"), "sha256").hexdigest()
(root / "vendor/UPSTREAM_ARCHIVE_SHA256").write_text(sha + "\n")
properties = root / "gradle/wrapper/gradle-wrapper.properties"
gradle_sum = Path("/tmp/jianxue-tools/gradle.zip.sha256")
if gradle_sum.exists():
    lines = [line for line in properties.read_text().splitlines() if not line.startswith("distributionSha256Sum=")]
    lines.append("distributionSha256Sum=" + gradle_sum.read_text().strip())
    properties.write_text("\n".join(lines) + "\n")
print("许可、固定上游校验值、TLS 补丁和 Gradle Wrapper 校验值已保存。")
