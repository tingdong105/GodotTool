"""按固定指纹准备 GDRE Android 核心及配套源码。"""

import concurrent.futures
import hashlib
import json
from pathlib import Path
import shutil
import urllib.request
import zipfile
import tomllib

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / "tools" / ".cache"
VENDOR = ROOT / "gdre-core" / "vendor"
ENGINE_COMMIT = "850c331d6f9f379c2c69a77f22701918271a6fe6"
APK_SHA256 = "5c7107e1def5f356b065b044737512822064b61cebad224a99bed78711c341ce"
APK_URL = "https://github.com/GDRETools/gdsdecomp/releases/download/v2.7.4/GDRE_tools-v2.7.4-android.apk"


def download(url: str, destination: Path, checksum: str = "") -> Path:
    """下载并验证文件，使用临时文件避免中断污染缓存。"""
    if destination.is_file():
        with destination.open("rb") as stream:
            if not checksum or hashlib.file_digest(stream, "sha256").hexdigest() == checksum:
                return destination
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix(".part")
    request = urllib.request.Request(url, headers={"User-Agent": "GodotTool-build"})
    with urllib.request.urlopen(request, timeout=120) as source, temporary.open("wb") as target:
        shutil.copyfileobj(source, target, 1024 * 1024)
    if checksum:
        with temporary.open("rb") as stream:
            if hashlib.file_digest(stream, "sha256").hexdigest() != checksum:
                raise ValueError("GDRE 下载文件 SHA-256 不匹配")
    temporary.replace(destination)
    return destination



def patch_service_io(path: Path) -> None:
    """修正上游 GodotIO 对 Activity 显示上下文和已挂载窗口的依赖。"""
    source = path.read_text(encoding="utf-8")
    old = """} else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			display = godot.getContext().getDisplay();
		}"""
    replacement = """} else {
			// GodotTool: application/service contexts have no associated display.
			android.hardware.display.DisplayManager manager =
					(android.hardware.display.DisplayManager) godot.getContext().getSystemService(Context.DISPLAY_SERVICE);
			display = manager != null ? manager.getDisplay(Display.DEFAULT_DISPLAY) : null;
		}"""
    if source.count(old) != 2:
        raise ValueError("Unexpected GodotIO display access; review upstream patch")
    source = source.replace(old, replacement)
    old_cutout = "DisplayCutout cutout = topView.getRootWindowInsets().getDisplayCutout();"
    if source.count(old_cutout) != 1:
        raise ValueError("Unexpected GodotIO insets access; review upstream patch")
    source = source.replace(old_cutout, """// GodotTool: the offscreen view is never attached to a window.
		WindowInsets windowInsets = topView.getRootWindowInsets();
		if (windowInsets == null) {
			return new int[0];
		}
		DisplayCutout cutout = windowInsets.getDisplayCutout();""")
    path.write_text(source, encoding="utf-8")


def prepare() -> None:
    """提取固定发行包与对应 Android Java/Kotlin 源码。"""
    CACHE.mkdir(parents=True, exist_ok=True)
    VENDOR.mkdir(parents=True, exist_ok=True)
    marker = VENDOR / "prepared.json"
    versions = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text(encoding="utf-8"))["versions"]
    expected = {"schema": 6, "gdre": "2.7.4", "engine": ENGINE_COMMIT, "apkSha256": APK_SHA256,
                "miuix": versions["miuix"], "mmkv": versions["mmkv"], "capstone": versions["capstone"]}
    if marker.is_file() and json.loads(marker.read_text()) == expected:
        print("GDRE 2.7.4 core ready", flush=True)
        return
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        apk = pool.submit(download, APK_URL, CACHE / "gdre-2.7.4.apk", APK_SHA256)
        source = pool.submit(download, f"https://codeload.github.com/nikitalita/godot/zip/{ENGINE_COMMIT}", CACHE / f"godot-{ENGINE_COMMIT}.zip")
        apk_path, source_path = apk.result(), source.result()
    with zipfile.ZipFile(apk_path) as archive:
        for name in archive.namelist():
            if name.startswith("lib/arm64-v8a/") and name.endswith(".so"):
                target = VENDOR / "jniLibs" / "arm64-v8a" / Path(name).name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(archive.read(name))
    prefix = f"godot-{ENGINE_COMMIT}/platform/android/java/lib/src/main/"
    with zipfile.ZipFile(source_path) as archive:
        for name in archive.namelist():
            if not name.startswith(prefix) or name.endswith("/"):
                continue
            relative = name[len(prefix):]
            if not relative.startswith(("java/", "res/", "aidl/")):
                continue
            target = VENDOR / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.read(name))
        license_text = archive.read(f"godot-{ENGINE_COMMIT}/LICENSE.txt")
        (VENDOR / "Godot-LICENSE.txt").write_bytes(license_text)
        copyright_text = archive.read(f"godot-{ENGINE_COMMIT}/COPYRIGHT.txt").decode("utf-8")
    patch_service_io(VENDOR / "java/org/godotengine/godot/GodotIO.java")
    licenses = ["Godot Engine\nhttps://github.com/nikitalita/godot/tree/" + ENGINE_COMMIT + "\n\n" + license_text.decode("utf-8") + "\n\n" + copyright_text]
    upstream = [
        ("GDRE Tools 2.7.4", "https://raw.githubusercontent.com/GDRETools/gdsdecomp/v2.7.4/LICENSE"),
        (f"miuix {versions['miuix']}", f"https://raw.githubusercontent.com/compose-miuix-ui/miuix/v{versions['miuix']}/LICENSE"),
        (f"MMKV {versions['mmkv']}", f"https://raw.githubusercontent.com/Tencent/MMKV/v{versions['mmkv']}/LICENSE.TXT"),
        (f"Capstone {versions['capstone']}", f"https://raw.githubusercontent.com/capstone-engine/capstone/{versions['capstone']}/LICENSE.TXT"),
    ]
    for path in ["atomic_queue/LICENSE", "axmldec/LICENSE.md", "dtl/LICENSE", "parallel_hashmap/LICENSE", "tga/LICENSE.txt", "tinygltf/LICENSE", "unsigned-hash-cpp/LICENSE", "vtracer/LICENSE-APACHE", "vtracer/LICENSE-MIT"]:
        upstream.append(("GDRE external/" + path, "https://raw.githubusercontent.com/GDRETools/gdsdecomp/v2.7.4/external/" + path))
    for name, url in upstream:
        path = download(url, CACHE / (name.replace(" ", "-").replace("/", "-") + "-LICENSE.txt"))
        licenses.append(name + "\n" + url + "\n\n" + path.read_text(encoding="utf-8"))
    licenses.append("AndroidX / Kotlin / kotlinx.coroutines\nhttps://www.apache.org/licenses/LICENSE-2.0\nLicensed under the Apache License, Version 2.0.\n" + licenses[2].split("\n\n", 1)[-1])
    assets = VENDOR / "assets"
    assets.mkdir(exist_ok=True)
    (assets / "licenses.txt").write_text("\n\n".join(licenses), encoding="utf-8")
    marker.write_text(json.dumps(expected, indent=2), encoding="utf-8")
    print("GDRE 2.7.4 Android core and bridge sources prepared", flush=True)


if __name__ == "__main__":
    prepare()
