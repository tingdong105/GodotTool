"""校验并准备仅用于静态分析的 Capstone 源码。"""

import json
from pathlib import Path
import shutil
import tomllib
import zipfile

from prepare_gdre import download

ROOT = Path(__file__).resolve().parents[1]
ARCHIVE_SHA256 = "3b3bdad8a75c4a9cbec28efd47778e412904db1588ec606d516b7196e8e6668e"


def prepare() -> None:
    """按版本目录下载源码，校验摘要并安全解压到忽略目录。"""
    version = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text(encoding="utf-8"))["versions"]["capstone"]
    target = ROOT / "app/vendor/capstone"
    marker = target / "prepared.json"
    expected = {"version": version, "sha256": ARCHIVE_SHA256}
    if marker.is_file() and json.loads(marker.read_text(encoding="utf-8")) == expected:
        print(f"Capstone {version} ready", flush=True)
        return
    archive = download(f"https://codeload.github.com/capstone-engine/capstone/zip/refs/tags/{version}",
                       ROOT / f"tools/.cache/capstone-{version}.zip", ARCHIVE_SHA256)
    prefix = f"capstone-{version}/"
    target.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive) as source:
        for item in source.infolist():
            if item.is_dir() or not item.filename.startswith(prefix):
                continue
            relative = Path(item.filename[len(prefix):])
            destination = (target / relative).resolve()
            if not destination.is_relative_to(target.resolve()):
                raise ValueError("Unsafe Capstone archive path")
            destination.parent.mkdir(parents=True, exist_ok=True)
            with source.open(item) as stream, destination.open("wb") as output:
                shutil.copyfileobj(stream, output)
    marker.write_text(json.dumps(expected, indent=2), encoding="utf-8")
    print(f"Capstone {version} sources verified and prepared", flush=True)


if __name__ == "__main__":
    prepare()
