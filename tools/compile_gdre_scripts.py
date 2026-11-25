"""使用固定 GDRE 编译器生成 APK 使用的 GDScript 字节码。"""

import argparse
from pathlib import Path
import platform
import shutil
import struct
import subprocess
import tempfile
import zipfile

from prepare_gdre import APK_URL, CACHE, ROOT, download


# 与 Android 核心同一发行版本的官方桌面工具指纹。
COMPILERS = {
    "Windows": ("windows", "af24c563eee501effb194d83595de64696c32547b2246034dbfb891a24bfa0ad"),
    "Linux": ("linux", "3dc4f1244dda62e41e0c952ac00a69e60e07f3b0d5a211164806bf9d41a54758"),
    "Darwin": ("macos", "a148b9509c2826fcc238e81c21005bf3128bdcee1a87c3f79ad943a205aa2170"),
}
BYTECODE_REVISION = "ebc36a7"
SOURCE = ROOT / "gdre-core" / "src" / "main" / "assets"


def compiler() -> Path:
    """校验并准备同版本编译器，返回桌面可执行文件路径。"""
    host = platform.system()
    if host not in COMPILERS:
        raise RuntimeError(f"Unsupported GDRE compiler host: {host}")
    suffix, checksum = COMPILERS[host]
    url = APK_URL.removesuffix("android.apk") + suffix + ".zip"
    archive = download(url, CACHE / ("gdre-compiler-" + suffix + ".zip"), checksum)
    destination = CACHE / "gdre-compiler" / suffix
    destination.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive) as package:
        for entry in package.infolist():
            target = (destination / entry.filename).resolve()
            if not target.is_relative_to(destination.resolve()):
                raise ValueError("Unsafe compiler archive entry")
            package.extract(entry, destination)
            if not entry.is_dir() and host != "Windows":
                target.chmod((entry.external_attr >> 16 & 0o777) or 0o755)
    candidates = [path for path in destination.rglob("*") if path.is_file() and (
        path.name.lower() == "gdre_tools.exe" if host == "Windows" else
        path.name == "gdre_tools.x86_64" if host == "Linux" else path.parent.name == "MacOS")]
    if len(candidates) != 1:
        raise RuntimeError("GDRE compiler executable not found unambiguously")
    return candidates[0]


def compile_assets(output: Path) -> None:
    """将启动资源和编译后的脚本写入生成目录；编译失败时中止构建。"""
    output = output.resolve()
    build = (ROOT / "gdre-core" / "build").resolve()
    if not output.is_relative_to(build) or output == build:
        raise ValueError("Generated assets must stay inside gdre-core/build")
    sources = sorted(SOURCE.rglob("*.gd"))
    if not sources:
        raise RuntimeError("No GDRE driver scripts found")
    executable = compiler()
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="gdre-compile-", dir=output.parent) as temporary:
        stage = Path(temporary)
        for source in sources:
            relative = source.relative_to(SOURCE).with_suffix(".gdc")
            folder = stage / relative.parent
            folder.mkdir(parents=True, exist_ok=True)
            command = [str(executable), "--headless", "--compile=" + str(source),
                       "--bytecode=" + BYTECODE_REVISION, "--output=" + str(folder)]
            result = subprocess.run(command, cwd=stage, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120)
            data_path = stage / relative
            if result.returncode or not data_path.is_file():
                raise RuntimeError(result.stdout.decode("utf-8", errors="replace")[-12000:])
            data = data_path.read_bytes()
            if len(data) < 12 or data[:4] != b"GDSC" or struct.unpack_from("<I", data, 4)[0] != 101:
                raise RuntimeError(f"Unexpected bytecode format: {source.name}")
        for source in SOURCE.rglob("*"):
            if source.is_file() and source.suffix != ".gd" and not any(part.startswith(".") for part in source.relative_to(SOURCE).parts):
                target = stage / source.relative_to(SOURCE)
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source, target)
        # 生成目录只允许打包字节码和静态资源。
        if any(output.rglob("*.gd")):
            raise RuntimeError("Unexpected GDScript source in generated assets; clean gdre-core/build first")
        expected = {source.relative_to(stage) for source in stage.rglob("*") if source.is_file()}
        for source in stage.rglob("*"):
            if source.is_file():
                target = output / source.relative_to(stage)
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source, target.with_suffix(target.suffix + ".part"))
                target.with_suffix(target.suffix + ".part").replace(target)
        # 同步移除已从源码删除的生成资源。
        for target in output.rglob("*"):
            if target.is_file() and target.relative_to(output) not in expected:
                target.unlink()
    print(f"Compiled {len(sources)} GDRE scripts; APK assets contain bytecode only", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    compile_assets(parser.parse_args().output)
