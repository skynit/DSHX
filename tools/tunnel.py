"""构建独立的 DSHX 穿透平台部署包。"""

import argparse
import json
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parents[1]
NAME = "dshx-tunnel-platform"


def build():
    core = ROOT / "core"
    # 复用平台测试使用的 ws 版本及完整性校验，不安装手机引擎的其他依赖。
    lock = json.loads((core / "package-lock.json").read_text(encoding="utf-8"))
    ws = lock["packages"]["node_modules/ws"]
    manifest = {"name": NAME, "private": True, "dependencies": {"ws": ws["version"]}}
    build_dir = ROOT / "build"
    build_dir.mkdir(exist_ok=True)
    archive = build_dir / f"{NAME}.tar.gz"
    with tempfile.TemporaryDirectory(prefix="tunnel-", dir=build_dir) as temporary:
        stage = Path(temporary)
        (stage / "package.json").write_text(json.dumps(manifest), encoding="utf-8")
        (stage / "package-lock.json").write_text(json.dumps({
            "name": NAME, "lockfileVersion": lock["lockfileVersion"],
            "packages": {"": manifest, "node_modules/ws": ws},
        }), encoding="utf-8")
        subprocess.run(["npm", "ci", "--omit=optional", "--ignore-scripts", "--no-audit", "--no-fund"],
                       cwd=stage, check=True)
        shutil.copy2(core / "tunnel-platform.mjs", stage)
        shutil.copy2(core / "web-access.mjs", stage)
        shutil.copytree(core / "web", stage / "web")
        shutil.copy2(ROOT / "LICENSE", stage)
        subprocess.run(["node", "--input-type=module", "-e", "import './tunnel-platform.mjs'"], cwd=stage, check=True)
        packaged = stage / archive.name
        with tarfile.open(packaged, "w:gz") as output:
            for name in ("tunnel-platform.mjs", "web-access.mjs", "web", "LICENSE", "node_modules/ws"):
                output.add(stage / name, arcname=f"{NAME}/{name}")
        packaged.replace(archive)
    print(archive)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["build"])
    parser.parse_args()
    build()
