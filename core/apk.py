"""在 Android 内使用 ECJ、D8 和 AAPT2 构建 Java 原生应用。"""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile
from xml.sax.saxutils import escape


ROOT = Path(__file__).resolve().parent
TOOLS = ROOT / "share/android"
ANDROID = "{http://schemas.android.com/apk/res/android}"


def art():
    for path in ("/apex/com.android.art/bin/dalvikvm64", "/apex/com.android.runtime/bin/dalvikvm64",
                 "/system/bin/dalvikvm64", "/system/bin/dalvikvm"):
        if os.access(path, os.X_OK):
            return path
    raise ValueError("此编译环境需要 Android ART，系统中没有可用的 dalvikvm。")


def run(arguments, *, capture=False):
    result = subprocess.run([str(value) for value in arguments], check=True, timeout=180,
                            stdout=subprocess.PIPE if capture else sys.stderr,
                            stderr=sys.stderr, text=True)
    return result.stdout if capture else None


def vm(tool, entry, *arguments):
    # 独立 ART 进程在 Android 14 起拒绝加载可写 DEX，包括包含 DEX 的 JAR。
    (TOOLS / tool).chmod(0o400)
    return [art(), "-Xmx256m", f"-Djava.io.tmpdir={os.environ['TMPDIR']}",
            "-cp", TOOLS / tool, entry, *arguments]


def check_tools():
    for name in ("android.jar", "core-lambda-stubs.jar", "ecj.jar", "d8.dex", "apksigner.dex", "toolchain.json"):
        if not (TOOLS / name).is_file():
            raise ValueError(f"预装 Android 编译环境缺少 {name}，请重新安装运行环境。")
    for name in ("aapt2", "openssl"):
        if not os.access(ROOT / "bin" / name, os.X_OK):
            raise ValueError(f"预装命令 {name} 不可用，请重新安装运行环境。")
    art()
    if not os.environ.get("TMPDIR") or not Path(os.environ["TMPDIR"]).is_dir():
        raise ValueError("宿主未提供可用的 TMPDIR。")
    return {**json.loads((TOOLS / "toolchain.json").read_text()), "vm": art()}


def init_project(path, package, name):
    if not re.fullmatch(r"[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+", package):
        raise ValueError("包名须由至少两段小写字母、数字或下划线组成，每段以字母开头。")
    if not name.strip() or len(name) > 80 or any(ord(char) < 32 for char in name):
        raise ValueError("应用名称须为 1 至 80 个可见字符。")
    label = escape(name).replace("\\", "\\\\").replace("'", "\\'").replace('"', '\\"')
    path = path.resolve()
    if path.exists():
        raise ValueError("项目目录已存在，请选择新的目录。")
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".dshx-project-", dir=path.parent) as directory:
        stage = Path(directory) / "project"
        main = stage / "app/src/main"
        source = main / "java" / package.replace(".", "/")
        source.mkdir(parents=True)
        (main / "res/values").mkdir(parents=True)
        (main / "res/values/strings.xml").write_text(
            f'<?xml version="1.0" encoding="utf-8"?>\n<resources><string name="app_name">"{label}"</string></resources>\n')
        for qualifier, parent, background, text, primary, light in (
                ("values", "Theme.Material.Light.NoActionBar", "#F7F7F5", "#202220", "#2F6CC4", "true"),
                ("values-night", "Theme.Material.NoActionBar", "#0E0E0E", "#ECECEC", "#78A7EF", "false")):
            folder = main / "res" / qualifier
            folder.mkdir(exist_ok=True)
            (folder / "colors.xml").write_text(
                f'<resources><color name="background">{background}</color>'
                f'<color name="text">{text}</color><color name="primary">{primary}</color></resources>\n')
            (folder / "styles.xml").write_text(
                f'<resources><style name="AppTheme" parent="android:style/{parent}">'
                '<item name="android:windowBackground">@color/background</item>'
                '<item name="android:colorBackground">@color/background</item>'
                '<item name="android:textColorPrimary">@color/text</item>'
                '<item name="android:colorAccent">@color/primary</item>'
                '<item name="android:statusBarColor">@color/background</item>'
                '<item name="android:navigationBarColor">@color/background</item>'
                f'<item name="android:windowLightStatusBar">{light}</item>'
                f'<item name="android:windowLightNavigationBar">{light}</item>'
                '</style></resources>\n')
        (main / "AndroidManifest.xml").write_text(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
            f'package="{package}" android:versionCode="1" android:versionName="1.0.0">\n'
            '    <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="28" />\n'
            '    <application android:label="@string/app_name" android:theme="@style/AppTheme" android:allowBackup="false">\n'
            '        <activity android:name=".MainActivity" android:exported="true">\n'
            '            <intent-filter>\n'
            '                <action android:name="android.intent.action.MAIN" />\n'
            '                <category android:name="android.intent.category.LAUNCHER" />\n'
            '            </intent-filter>\n'
            '        </activity>\n'
            '    </application>\n'
            '</manifest>\n')
        (source / "MainActivity.java").write_text(
            f'package {package};\n\n'
            'import android.app.Activity;\nimport android.os.Bundle;\nimport android.widget.TextView;\n\n'
            'public class MainActivity extends Activity {\n'
            '    @Override public void onCreate(Bundle state) {\n'
            '        super.onCreate(state);\n'
            '        TextView text = new TextView(this);\n'
            '        text.setText(R.string.app_name);\n'
            '        text.setTextSize(24);\n'
            '        int padding = Math.round(24 * getResources().getDisplayMetrics().density);\n'
            '        text.setPadding(padding, padding, padding, padding);\n'
            '        setContentView(text);\n'
            '    }\n}\n')
        stage.rename(path)
    return {"project": str(path), "packageName": package}


def project_info(path):
    main = path / "app/src/main"
    manifest = ET.parse(main / "AndroidManifest.xml").getroot()
    package = manifest.get("package", "")
    if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+", package):
        raise ValueError("AndroidManifest.xml 缺少有效 package。")
    sdk = manifest.find("uses-sdk")
    if sdk is None:
        raise ValueError("AndroidManifest.xml 须声明 uses-sdk。")
    minimum = int(sdk.get(ANDROID + "minSdkVersion", "0"))
    target = int(sdk.get(ANDROID + "targetSdkVersion", "0"))
    if not 26 <= minimum <= target <= 34:
        raise ValueError("此工具链支持 minSdkVersion ≥ 26、targetSdkVersion ≤ 34，且 target 不小于 min。")
    if int(manifest.get(ANDROID + "versionCode", "0")) <= 0 or not manifest.get(ANDROID + "versionName"):
        raise ValueError("AndroidManifest.xml 须声明正整数 versionCode 和 versionName。")
    if any(main.rglob("*.kt")) or any((path / "app/libs").glob("*.aar")):
        raise ValueError("当前支持 Java 源码和本地 JAR；Kotlin、AAR 需要其他构建环境。")
    if (main / "jniLibs").exists():
        raise ValueError("当前不打包原生库；jniLibs 需要另外的原生构建与对齐验证。")
    sources = sorted((main / "java").rglob("*.java"))
    if not sources:
        raise ValueError("app/src/main/java 中没有 Java 源码。")
    return main, package, minimum, sources, sorted((path / "app/libs").glob("*.jar"))


def signing_key():
    directory = Path(os.environ["HOME"]) / ".local/share/dshx/android/signing"
    directory.parent.mkdir(parents=True, exist_ok=True)
    # 同一安装内并发构建不同项目，也只生成一次私钥。
    with (directory.parent / "signing.lock").open("a") as lock:
        os.chmod(lock.name, 0o600)
        fcntl.flock(lock, fcntl.LOCK_EX)
        key, certificate = directory / "key.pk8", directory / "certificate.pem"
        if directory.exists():
            if not key.is_file() or not certificate.is_file():
                raise ValueError("签名目录不完整，请恢复原私钥和证书，不能自动替换更新身份。")
            return key, certificate
        with tempfile.TemporaryDirectory(prefix=".signing-", dir=directory.parent) as temporary:
            stage = Path(temporary) / "key"
            stage.mkdir(mode=0o700)
            openssl = ROOT / "bin/openssl"
            run([openssl, "req", "-config", "/dev/null", "-x509", "-newkey", "rsa:2048", "-sha256", "-noenc", "-days", "10000",
                 "-subj", "/CN=DSHX Local Android Apps", "-keyout", stage / "private.pem", "-out", stage / "certificate.pem"])
            run([openssl, "pkcs8", "-topk8", "-nocrypt", "-in", stage / "private.pem", "-outform", "DER", "-out", stage / "key.pk8"])
            (stage / "private.pem").unlink()
            for file in stage.iterdir():
                file.chmod(0o600)
            stage.rename(directory)
        return key, certificate


def add_code(archive, dex, libraries):
    with zipfile.ZipFile(archive, "a", compression=zipfile.ZIP_DEFLATED) as target:
        for file in sorted(dex.glob("classes*.dex")):
            target.write(file, file.name)
        # JAR 的运行资源也要保留；类交给 D8，签名和清单由当前 APK 自己生成。
        seen = set(target.namelist())
        for library in libraries:
            with zipfile.ZipFile(library) as source:
                for item in source.infolist():
                    name = item.filename
                    if item.is_dir() or name.endswith(".class") or name.startswith("META-INF/"):
                        continue
                    if name.startswith("/") or ".." in Path(name).parts or "\\" in name or name in seen:
                        raise ValueError(f"JAR 资源路径无效或冲突：{name}")
                    target.writestr(name, source.read(item))
                    seen.add(name)


def align_apk(source, destination):
    # APK 内无原生 .so；仅对未压缩条目做四字节 ZIP 对齐，避免额外分发 zipalign。
    with zipfile.ZipFile(source) as original, zipfile.ZipFile(destination, "w") as target:
        for entry in original.infolist():
            entry.extra = b""
            if entry.compress_type == zipfile.ZIP_STORED:
                padding = -(target.fp.tell() + 30 + len(entry.filename.encode("utf-8")) + 6) % 4
                entry.extra = struct.pack("<HHH", 0xD935, padding + 2, 4) + bytes(padding)
            target.writestr(entry, original.read(entry))


def build_project(path, output):
    check_tools()
    path = path.resolve()
    main, package, minimum, sources, libraries = project_info(path)
    build = path / "build"
    build.mkdir(exist_ok=True)
    output = (output or build / "app.apk").resolve()
    if output.suffix != ".apk":
        raise ValueError("输出文件须使用 .apk 扩展名。")
    with (build / ".dshx-apk.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        with tempfile.TemporaryDirectory(prefix=".dshx-apk-", dir=build) as directory:
            work = Path(directory)
            generated, classes, dex = (work / name for name in ("generated", "classes", "dex"))
            for folder in (generated, classes, dex):
                folder.mkdir()
            unsigned = work / "unsigned.apk"
            compiled = work / "resources.zip"
            flats = []
            if (main / "res").is_dir() and any(file.is_file() for file in (main / "res").rglob("*")):
                run([ROOT / "bin/aapt2", "compile", "--dir", main / "res", "-o", compiled])
                flats = [compiled]
            link = [ROOT / "bin/aapt2", "link", "-I", TOOLS / "android.jar", "--manifest", main / "AndroidManifest.xml",
                    "--java", generated, "-o", unsigned, *flats]
            if (main / "assets").is_dir():
                link += ["-A", main / "assets"]
            run(link)
            run(vm("ecj.jar", "org.eclipse.jdt.internal.compiler.batch.Main", "-proc:none", "-1.8", "-encoding", "UTF-8",
                   "-bootclasspath", os.pathsep.join(str(TOOLS / name) for name in ("android.jar", "core-lambda-stubs.jar")),
                   "-classpath", os.pathsep.join(map(str, [TOOLS / "android.jar", *libraries])),
                   "-d", classes, *sources, *sorted(generated.rglob("*.java"))))
            # 用 JAR 传递所有 class，避免项目稍大时超过进程参数长度限制。
            compiled_java = work / "classes.jar"
            with zipfile.ZipFile(compiled_java, "w") as archive:
                for file in sorted(classes.rglob("*.class")):
                    archive.write(file, file.relative_to(classes).as_posix())
            run(vm("d8.dex", "com.android.tools.r8.D8", "--lib", TOOLS / "android.jar", "--min-api", minimum,
                   "--output", dex, compiled_java, *libraries))
            if not (dex / "classes.dex").is_file():
                raise ValueError("DEX 转换没有生成 classes.dex。")
            add_code(unsigned, dex, libraries)
            aligned, signed = work / "aligned.apk", work / "signed.apk"
            align_apk(unsigned, aligned)
            key, certificate = signing_key()
            run(vm("apksigner.dex", "com.android.apksigner.ApkSignerTool", "sign", "--key", key, "--cert", certificate,
                   "--min-sdk-version", minimum, "--v1-signing-enabled", "false", "--v2-signing-enabled", "true",
                   "--v3-signing-enabled", "false", "--v4-signing-enabled", "false", "--out", signed, aligned))
            run(vm("apksigner.dex", "com.android.apksigner.ApkSignerTool", "verify", "--min-sdk-version", minimum, signed))
            output.parent.mkdir(parents=True, exist_ok=True)
            # 输出可在其他文件系统；先写同目录暂存文件，验证完成才替换上次成功产物。
            handle, temporary = tempfile.mkstemp(prefix=".dshx-apk-", dir=output.parent)
            try:
                with os.fdopen(handle, "wb") as target, signed.open("rb") as source:
                    shutil.copyfileobj(source, target)
                with signed.open("rb") as source:
                    digest = hashlib.file_digest(source, "sha256").hexdigest()
                receipt = {"path": str(output), "packageName": package, "size": signed.stat().st_size,
                           "sha256": digest, "signatureVerified": True}
                os.replace(temporary, output)
                return receipt
            finally:
                Path(temporary).unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__, epilog="支持 Java 8、API 26–34 和 app/libs/*.jar；不运行 Gradle，不解析 Maven/AAR/Kotlin。")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("doctor", help="检查预装工具并显示固定版本")
    create = commands.add_parser("init", help="在新目录创建最小原生 Java 工程")
    create.add_argument("path", type=Path)
    create.add_argument("--package", required=True)
    create.add_argument("--name", default="我的应用")
    build = commands.add_parser("build", help="编译、打包、签名并验签；成功后输出 JSON 文件回执")
    build.add_argument("path", nargs="?", type=Path, default=Path.cwd())
    build.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        if args.command == "doctor":
            result = check_tools()
        elif args.command == "init":
            result = init_project(args.path, args.package, args.name)
        else:
            result = build_project(args.path, args.output)
        print(json.dumps(result, ensure_ascii=False))
        return 0
    except (OSError, ValueError, KeyError, ET.ParseError, zipfile.BadZipFile, subprocess.SubprocessError) as error:
        print(f"Android 构建失败：{error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
