#!/usr/bin/env python3
"""显式创建本地发布密钥；已有密钥始终保留，凭据不进入源码与 Release。"""
from pathlib import Path
import json
import os
import secrets
import subprocess

root = Path(__file__).resolve().parent.parent
directory = Path(os.environ.get("JIANXUE_SIGNING_DIR", root / ".release-signing")).resolve()
directory.mkdir(parents=True, exist_ok=True, mode=0o700)
config = directory / "signing.json"
keystore = directory / "jianxue-release.keystore"
if config.exists() and keystore.exists():
    print(f"保留已有发布签名：{keystore}")
    raise SystemExit(0)
if config.exists() or keystore.exists():
    raise SystemExit("签名文件不完整，请恢复原签名备份；不会覆盖或自动换用新密钥。")
password = secrets.token_urlsafe(40)
credentials = {"keystore": keystore.name, "store_password": password,
               "key_alias": "jianxue", "key_password": password}
with config.open("x") as output:
    json.dump(credentials, output, indent=2)
config.chmod(0o600)
environment = os.environ.copy()
environment["JIANXUE_NEW_KEY_PASSWORD"] = password
tool_dir = Path(os.environ.get("JIANXUE_TOOLS", "/tmp/jianxue-tools"))
keytool = Path(os.environ.get("JAVA_HOME", tool_dir / "jdk")) / "bin/keytool"
try:
    subprocess.run([str(keytool), "-genkeypair", "-keystore", str(keystore), "-storetype", "JKS",
                    "-storepass:env", "JIANXUE_NEW_KEY_PASSWORD", "-keypass:env", "JIANXUE_NEW_KEY_PASSWORD",
                    "-alias", "jianxue", "-keyalg", "RSA", "-keysize", "4096", "-validity", "10000",
                    "-dname", "CN=Jianxue Release,O=Jianxue,C=CN"], env=environment, check=True)
except Exception:
    if not keystore.exists():
        config.unlink()
    raise
keystore.chmod(0o600)
(directory / "README.txt").write_text(
    "此目录是简学输入法的私人发布签名备份，不属于开源源码或 Release 附件。\n"
    "请将整个目录复制到安全的离线备份位置，保留 keystore 与 signing.json。\n"
    "后续版本使用同一份签名才能正常覆盖升级。不要上传此目录、公开密码或重新生成签名。\n"
    "恢复时可设置 JIANXUE_SIGNING_DIR 指向备份目录，再运行 bash scripts/dev.sh release。\n")
print(f"发布签名已保存：{directory}；请备份整个目录。密码不会输出。")
