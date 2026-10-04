#!/usr/bin/env python3
"""验证包中正式资源、JNI 来源和 Android 16 KB ELF/ZIP 页对齐。"""
from pathlib import Path
import hashlib
import json
import struct
import zipfile
import argparse

root = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("apk", nargs="?", default="dist/jianxue-debug.apk")
parser.add_argument("--output", default="dist/artifact-verification.json")
args = parser.parse_args()
apk = root / args.apk
result = {"apk": apk.name, "abis": [], "formal_resources": True}
with zipfile.ZipFile(apk) as bundle:
    for resource in ["dict.qj", "lm.qj", "model.qjm", "glossary-en.qj", "glossary-ja.qj", "glossary-es.qj", "licenses/LICENSE", "licenses/NOTICE.md"]:
        assert bundle.getinfo("assets/" + resource).file_size > 0, resource
    with apk.open("rb") as stream:
        for abi in ["arm64-v8a", "x86_64"]:
            name = f"lib/{abi}/libjianxue.so"
            entry = bundle.getinfo(name)
            code = bundle.read(name)
            expected = (root / f"app/src/main/jniLibs/{abi}/libjianxue.so").read_bytes()
            # Gradle may strip symbols. The ELF must still carry our JNI exports.
            for export in [b"Java_org_jianxue_ime_engine_NativeEngine_create", b"Java_org_jianxue_ime_engine_NativeEngine_dispatch", b"Java_org_jianxue_ime_engine_NativeEngine_destroy"]:
                assert export in code, export
            assert code[:5] == b"\x7fELF\x02", abi
            offset = struct.unpack_from("<Q", code, 32)[0]
            size, count = struct.unpack_from("<HH", code, 54)
            alignments = []
            for index in range(count):
                fields = struct.unpack_from("<IIQQQQQQ", code, offset + index * size)
                if fields[0] == 1:
                    alignments.append(fields[7])
                    assert fields[7] >= 16384, (abi, fields[7])
            assert alignments
            stream.seek(entry.header_offset)
            header = stream.read(30)
            filename, extra = struct.unpack_from("<HH", header, 26)
            data_offset = entry.header_offset + 30 + filename + extra
            assert entry.compress_type == zipfile.ZIP_STORED and data_offset % 16384 == 0, (abi, data_offset)
            result["abis"].append({"abi": abi, "elf_alignments": alignments, "zip_offset": data_offset,
                                  "sha256": hashlib.sha256(code).hexdigest(), "same_unstripped_file": code == expected})
(root / args.output).write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
print("APK 资源、JNI 导出、两种 ABI 的 ELF/ZIP 16 KB 对齐已验证。")
