#!/usr/bin/env python3
"""使用维护者发布密钥构建并验证正式 APK，拒绝缺少签名的发布。"""
from pathlib import Path
import hashlib
import json
import os
import re
import shutil
import subprocess
import zipfile

root = Path(__file__).resolve().parent.parent
os.chdir(root)
revision = None
if (root / ".git").exists():
    assert not subprocess.check_output(["git", "status", "--porcelain"]).strip(), "正式发布前请先提交源码，确保 APK 对应固定提交"
    revision = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
environment = os.environ.copy()
if not environment.get("JIANXUE_RELEASE_KEYSTORE"):
    directory = Path(environment.get("JIANXUE_SIGNING_DIR", root / ".release-signing")).resolve()
    config = directory / "signing.json"
    if not config.is_file():
        raise SystemExit("缺少发布密钥。首次运行 python3 scripts/create-release-key.py，并备份生成的私人目录。")
    credentials = json.loads(config.read_text())
    environment.update({"JIANXUE_RELEASE_KEYSTORE": str(directory / credentials["keystore"]),
                        "JIANXUE_RELEASE_STORE_PASSWORD": credentials["store_password"],
                        "JIANXUE_RELEASE_KEY_ALIAS": credentials["key_alias"],
                        "JIANXUE_RELEASE_KEY_PASSWORD": credentials["key_password"]})
for name in ["JIANXUE_RELEASE_KEYSTORE", "JIANXUE_RELEASE_STORE_PASSWORD", "JIANXUE_RELEASE_KEY_ALIAS",
             "JIANXUE_RELEASE_KEY_PASSWORD"]:
    if not environment.get(name):
        raise SystemExit(f"缺少发布配置：{name}")
keystore = Path(environment["JIANXUE_RELEASE_KEYSTORE"])
assert keystore.is_file(), "发布密钥文件不存在"
configuration = (root / "app/build.gradle").read_text()
version = re.search(r"versionName '([^']+)'", configuration).group(1)
version_code = int(re.search(r"versionCode (\d+)", configuration).group(1))
assert not version.endswith("-dev"), "正式发布版本不能带 -dev"
print(f"构建正式 APK：{version}，versionCode {version_code}", flush=True)
subprocess.run(["bash", "scripts/dev.sh", "native"], env=environment, check=True)
subprocess.run(["gradle", "--no-daemon", ":app:assembleRelease", ":app:testReleaseUnitTest", ":app:lintRelease"],
               env=environment, check=True)
dist = root / "dist"
dist.mkdir(exist_ok=True)
apk = dist / f"jianxue-{version}-release.apk"
stage = apk.with_suffix(".apk.next")
shutil.copyfile(root / "app/build/outputs/apk/release/app-release.apk", stage)
stage.replace(apk)
sdk = Path(environment["ANDROID_HOME"])
tools = sdk / "build-tools/35.0.0"
signature = subprocess.check_output([str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk)],
                                    env=environment, text=True)
assert "Android Debug" not in signature, "正式 APK 不能使用 Android 调试证书"
certificate = subprocess.check_output([str(Path(environment["JAVA_HOME"]) / "bin/keytool"), "-exportcert",
                                        "-keystore", str(keystore), "-storepass:env", "JIANXUE_RELEASE_STORE_PASSWORD",
                                        "-alias", environment["JIANXUE_RELEASE_KEY_ALIAS"]], env=environment)
fingerprint = hashlib.sha256(certificate).hexdigest()
assert re.search(r"certificate SHA-256 digest: (\w+)", signature).group(1) == fingerprint, "APK 发布证书不匹配"
badging = subprocess.check_output([str(tools / "aapt"), "dump", "badging", str(apk)], env=environment, text=True)
assert "application-debuggable" not in badging, "APK 仍启用了调试"
assert f"versionName='{version}'" in badging and f"versionCode='{version_code}'" in badging, "APK 版本号不一致"
assert "name='org.jianxue.ime'" in badging, "APK 包名不一致"
if revision:
    with zipfile.ZipFile(apk) as bundle:
        provenance = bundle.read("META-INF/version-control-info.textproto").decode()
    assert f'revision: "{revision}"' in provenance, "APK 中的源码提交与当前发布提交不一致"
subprocess.run(["python3", "scripts/verify-artifacts.py", str(apk)], env=environment, check=True)
(dist / "release-signature.txt").write_text(signature)
report = {"apk": apk.name, "version": version, "version_code": version_code,
          "build_type": "release", "debuggable": False, "signature_verified": True, "source_commit": revision,
          "signer_sha256": fingerprint, "abis": ["arm64-v8a", "x86_64"]}
(dist / "release-verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
print(f"正式 APK 验证通过：{apk.name}；未启用调试，发布证书 SHA-256：{fingerprint}")
