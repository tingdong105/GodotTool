"""验证 APK 签名、ZIP 对齐与全部原生库的 16 KB ELF 对齐。"""

import argparse
import hashlib
from pathlib import Path
import struct
import subprocess
import zipfile


def inspect(apk: Path, build_tools: Path) -> None:
    """检查指定 APK 的可分发结构，不安装或启动设备。"""
    with zipfile.ZipFile(apk) as archive:
        libraries = [entry for entry in archive.infolist() if entry.filename.startswith("lib/") and entry.filename.endswith(".so")]
        if not libraries:
            raise AssertionError("APK has no native core")
        for entry in libraries:
            if not entry.filename.startswith("lib/arm64-v8a/"):
                raise AssertionError("Unexpected ABI: " + entry.filename)
            data = archive.read(entry)
            assert data[:6] == b"\x7fELF\x02\x01", entry.filename
            assert struct.unpack_from("<H", data, 18)[0] == 183, entry.filename
            offset = struct.unpack_from("<Q", data, 32)[0]
            size, count = struct.unpack_from("<HH", data, 54)
            segments = 0
            for index in range(count):
                position = offset + index * size
                if struct.unpack_from("<I", data, position)[0] != 1:
                    continue
                file_offset, address = struct.unpack_from("<QQ", data, position + 8)
                alignment = struct.unpack_from("<Q", data, position + 48)[0]
                assert alignment >= 16384 and alignment & (alignment - 1) == 0, entry.filename
                assert (address - file_offset) % 16384 == 0, entry.filename
                segments += 1
            assert segments > 0, entry.filename
            print(entry.filename + ": 16 KB ELF alignment verified")
        assert "get_import_files(false)" in archive.read("assets/gdre/driver.gd").decode("utf-8")
        assert 'config/name="GodotTool Core"' in archive.read("assets/project.godot").decode("utf-8")
        assert "assets/licenses.txt" in archive.namelist()
        assert "Capstone" in archive.read("assets/licenses.txt").decode("utf-8")
        assert "lib/arm64-v8a/libcnm_capstone.so" in archive.namelist()
        locator = archive.read("lib/arm64-v8a/libcnm_capstone.so")
        assert b"Java_com_winter_godot_tool_feature_CNM_CapstoneLocator_nativeReferences" in locator
        dex = b"".join(archive.read(name) for name in archive.namelist() if name.endswith(".dex"))
        assert b"Lcom/winter/godot/core/engine/OffscreenRenderView;" in dex, "JNI offscreen view was removed or renamed"
        assert b"Lcom/winter/godot/tool/feature/CNM/CapstoneLocator;" in dex, "Capstone JNI class was removed or renamed"
        assert b"nativeReferences" in dex, "Capstone JNI entry was renamed"
        for method in (b"configurePointerIcon", b"setPointerIcon", b"canCapturePointer", b"getRenderView"):
            assert method in dex, "Missing JNI view method: " + method.decode()

    signer = build_tools / ("apksigner.bat" if (build_tools / "apksigner.bat").exists() else "apksigner")
    aligner = build_tools / ("zipalign.exe" if (build_tools / "zipalign.exe").exists() else "zipalign")
    subprocess.run([str(signer), "verify", "--verbose", str(apk)], check=True)
    subprocess.run([str(aligner), "-c", "-P", "16", "4", str(apk)], check=True)
    with apk.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    print(f"APK: {apk}\nSize: {apk.stat().st_size} bytes\nSHA-256: {digest}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("--build-tools", type=Path, required=True)
    args = parser.parse_args()
    inspect(args.apk.resolve(), args.build_tools.resolve())
