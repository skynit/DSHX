"""准备供 Android ART 执行的固定版本 Java 编译工具。"""
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile


ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / "core/cache/android"


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def install(bundle):
    lock = json.loads((ROOT / "core/android.lock.json").read_text())
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or Path.home() / "Android/Sdk")
    CACHE.mkdir(parents=True, exist_ok=True)

    def prepare(pair):
        name, item = pair
        if "sdkPath" in item:
            path = sdk / item["sdkPath"]
        else:
            path = CACHE / name
            if not path.is_file() or digest(path) != item["sha256"]:
                handle, temporary = tempfile.mkstemp(prefix="download-", dir=CACHE)
                os.close(handle)
                try:
                    for url in (item["url"], item["apiUrl"]):
                        result = subprocess.run(["curl", "-fLsS", "--retry", "1", "--max-time", "180",
                                                 "-H", "Accept: application/octet-stream", url, "-o", temporary])
                        if result.returncode == 0 and digest(Path(temporary)) == item["sha256"]:
                            break
                    else:
                        raise ValueError("Android 编译工具下载或校验失败：" + name)
                    Path(temporary).replace(path)
                finally:
                    Path(temporary).unlink(missing_ok=True)
        if not path.is_file() or digest(path) != item["sha256"]:
            raise ValueError("Android 编译工具版本不匹配：" + str(path))
        return name, path

    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        tools = dict(pool.map(prepare, lock["tools"].items()))
    destination = bundle / "share/android"
    destination.mkdir(parents=True, exist_ok=True)
    licenses = bundle / "share/licenses/android-compiler"
    licenses.mkdir(parents=True, exist_ok=True)
    for name, source in tools.items():
        shutil.copy2(source, (licenses if name in ("package.xml", "kosei-LICENSE.txt", "EPL-2.0.txt", "Apache-2.0.txt") else destination) / name)
    with zipfile.ZipFile(tools["ecj.jar"]) as source:
        (licenses / "ecj-about.html").write_bytes(source.read("about.html"))
    (licenses / "SOURCES.txt").write_text(
        "ECJ 3.27.0 (EPL-2.0): https://github.com/eclipse-jdt/eclipse.jdt.core\n"
        "Original ECJ sources: https://repo1.maven.org/maven2/org/eclipse/jdt/ecj/3.27.0/ecj-3.27.0-sources.jar\n"
        "ECJ sources and ART compatibility patches: https://github.com/farhaanaliii/kosei-toolchain/tree/v0.1.0\n"
        "ECJ includes javax stubs from com.zeoflow:jx:1.2.1 (Apache-2.0): https://github.com/zeoflow/jx\n"
        "D8 / apksigner (Apache-2.0): https://android.googlesource.com/platform/tools/\n"
        "AAPT2: https://github.com/termux/android-build-tools (licenses in the Termux package notices)\n")
    (destination / "toolchain.json").write_text(json.dumps({
        "format": 1, "compileSdk": 34, "java": "8", "ecj": "3.27.0",
        "runtime": "Android ART", "heapLimitMiB": 256,
        "source": "kosei-toolchain/v0.1.0", "artifacts": lock["tools"],
    }, ensure_ascii=False, indent=2) + "\n")
    shutil.copy2(ROOT / "core/apk.py", bundle / "apk.py")
    (bundle / "bin/dshx-apk").write_text(
        '#!/system/bin/sh\nbin=${0%/*}\nexec "$bin/python3" "$bin/../apk.py" "$@"\n')
    return {"bin/dshx-apk"}
