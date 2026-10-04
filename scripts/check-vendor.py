#!/usr/bin/env python3
"""核对源码归档与 Cargo.lock 中所有注册表依赖及其原包校验值。"""
import json
import tomllib
from pathlib import Path

root = Path(__file__).resolve().parent.parent
directory = root / "third-party/rust"
marker = directory / ".jianxue-complete"
if marker.exists():
    marker.unlink()
packages = {}
for path in directory.glob("*/Cargo.toml"):
    package = tomllib.loads(path.read_text())["package"]
    checksum = path.parent / ".cargo-checksum.json"
    if checksum.exists():
        packages[(package["name"], package["version"])] = json.loads(checksum.read_text())["package"]
expected = tomllib.loads((root / "native/Cargo.lock").read_text())["package"]
count = 0
for package in expected:
    if package.get("source", "").startswith("registry+"):
        key = (package["name"], package["version"])
        assert packages.get(key) == package["checksum"], f"依赖源码不完整：{key}"
        count += 1
marker.write_text(f"{count} registry packages verified against native/Cargo.lock\n")
print(f"第三方原始源码与许可：{count} 个注册表依赖已核对。")
