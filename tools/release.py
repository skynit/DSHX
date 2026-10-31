#!/usr/bin/env python3
"""发布 Android OTA：从已推送提交生成更新说明，签署清单并原子发布。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import tempfile
from datetime import datetime, timezone
from urllib.error import HTTPError
from urllib.parse import urlparse
from urllib.request import urlopen
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_URL = "http://47.119.120.17/dshx"
REMOTE_ROOT = "/opt/dshx/updates"


def run(*args, **kwargs):
    return subprocess.run(args, check=True, text=True, capture_output=True, cwd=ROOT, **kwargs).stdout.strip()


def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def key_operation(mode, payload=b"", signature=""):
    # 私钥只由本机 JDK 读取；密码从环境读取，不进入命令行、仓库或服务器。
    source = """
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
class ReleaseSign {
  public static void main(String[] args) throws Exception {
    char[] password = System.getenv("DSHX_STORE_PASSWORD").toCharArray();
    KeyStore store = KeyStore.getInstance(new File(args[1]), password);
    var certificate = store.getCertificate(args[2]);
    if (args[0].equals("certificate")) {
      System.out.print(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded())));
      return;
    }
    Signature signer = Signature.getInstance("SHA256withRSA");
    if (args[0].equals("sign")) signer.initSign((PrivateKey)store.getKey(args[2], password));
    else signer.initVerify(certificate);
    signer.update(Files.readAllBytes(Path.of(args[3])));
    if (args[0].equals("sign")) System.out.print(Base64.getEncoder().encodeToString(signer.sign()));
    else if (!signer.verify(Base64.getDecoder().decode(args[4]))) throw new SecurityException("清单签名不匹配");
  }
}
"""
    key = Path(os.environ.get("DSHX_STORE_FILE", str(Path.home() / ".android/debug.keystore"))).expanduser().resolve()
    if not key.is_file():
        raise ValueError("找不到本机签名密钥，请配置 DSHX_STORE_FILE。")
    environment = os.environ.copy()
    environment.setdefault("DSHX_STORE_PASSWORD", "android")
    with tempfile.TemporaryDirectory(prefix="dshx-release-") as directory:
        java = Path(directory) / "ReleaseSign.java"
        java.write_text(source, encoding="utf-8")
        data = Path(directory) / "payload"
        data.write_bytes(payload)
        return run("java", str(java), mode, str(key), os.environ.get("DSHX_KEY_ALIAS", "androiddebugkey"), str(data), signature, env=environment)


def verified_manifest(data):
    if len(data) > 64 * 1024:
        raise ValueError("版本清单过大。")
    value = json.loads(data)
    signature = value.pop("signature")
    key_operation("verify", canonical(value), signature)
    return value


def latest(base):
    try:
        with urlopen(base + "/latest.json", timeout=15) as response:
            if response.url != base + "/latest.json":
                raise ValueError("更新地址发生跳转，请核对服务器。")
            return verified_manifest(response.read(64 * 1024 + 1))
    except HTTPError as error:
        if error.code == 404:
            return None
        raise


def sdk_tool(name):
    properties = ROOT / "local.properties"
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk and properties.is_file():
        match = re.search(r"^sdk.dir=(.+)$", properties.read_text(), re.M)
        sdk = match.group(1).strip() if match else None
    directory = Path(sdk or Path.home() / "Android/Sdk") / "build-tools"
    versions = sorted((p for p in directory.iterdir() if re.fullmatch(r"\d+(\.\d+)+", p.name)),
                      key=lambda p: tuple(map(int, p.name.split("."))), reverse=True)
    return str(next(p / name for p in versions if (p / name).is_file()))


def base_url(value):
    value = value.rstrip("/")
    parsed = urlparse(value)
    if parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError("下载地址必须是完整 HTTP/HTTPS 地址，不能包含账号、查询参数或片段。")
    return value


def prepare(args):
    apk = Path(args.apk).expanduser().resolve()
    base = base_url(args.base_url)
    run("git", "fetch", "origin")
    with zipfile.ZipFile(apk) as archive:
        info = json.loads(archive.read("assets/build-info.json"))
        core = json.loads(archive.read("assets/core-info.json"))
        native_abis = {name.split("/")[1] for name in archive.namelist() if name.startswith("lib/") and name.endswith(".so")}
    if info["dirty"] or core["abi"] != "arm64-v8a" or native_abis != {"arm64-v8a"}:
        raise ValueError("只能发布从干净提交构建、运行包与原生库均为 arm64 的 APK。请提交并推送后重新构建。")
    if info["updateUrl"] != base + "/latest.json":
        raise ValueError("APK 内置更新地址与发布地址不一致。")
    commit = info["commit"]
    if not re.fullmatch(r"[a-f0-9]{40}", commit):
        raise ValueError("APK 内的构建提交无效。")
    run("git", "merge-base", "--is-ancestor", commit, "origin/master")
    signing = run(sdk_tool("apksigner"), "verify", "--print-certs", str(apk))
    fingerprints = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]+)$", signing, re.M)
    if fingerprints != [key_operation("certificate")]:
        raise ValueError("APK 签名与发布清单使用的本机密钥不一致。")
    badging = run(sdk_tool("aapt"), "dump", "badging", str(apk))
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
    if not package or package.group(1) != "local.dshx.app":
        raise ValueError("APK 不是 DSHX 主应用。")
    code, name = int(package.group(2)), package.group(3)
    if code not in range(1, 2_100_000_001) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,79}", name):
        raise ValueError("版本名称不能用于文件名。")
    previous = latest(base)
    if previous and code <= previous["versionCode"]:
        raise ValueError("发布编号必须大于服务器上的当前版本。")
    old_commit = previous["commit"] if previous else None
    if old_commit:
        run("git", "merge-base", "--is-ancestor", old_commit, commit)
    history = run("git", "log", "--reverse", "--format=%h %s", f"{old_commit}..{commit}" if old_commit else commit, "--")
    notes = (f"DSHX {name}\n版本编号：{code}\n发布时间：{datetime.now(timezone.utc).isoformat(timespec='seconds')}\n\n"
             f"本次更新提交：\n\n{history or '本次发布没有新增代码提交。'}\n\n"
             f"代码仓库：https://github.com/skynit/DSHX\n本次提交：{commit}\n"
             f"上次发布：{old_commit or '首次发布，包含当前提交之前的完整历史'}\n").encode("utf-8")
    if len(notes) > 128 * 1024 or not 0 < apk.stat().st_size <= 1024 ** 3:
        raise ValueError("更新说明或安装包超过客户端允许的大小。")
    output = Path(args.output).resolve() / str(code)
    if output.exists():
        raise ValueError(f"发布目录已存在，不能覆盖：{output}")
    output.mkdir(parents=True)
    filename = f"DSHX-arm64-{name}.apk"
    shutil.copyfile(apk, output / filename)
    (output / "release-note.txt").write_bytes(notes)
    minimum = re.search(r"^sdkVersion:'(\d+)'", badging, re.M)
    release = {
        "format": 1, "packageName": "local.dshx.app", "versionCode": code, "versionName": name,
        "commit": commit, "previousCommit": old_commit,
        "releaseNotesUrl": f"{base}/releases/{code}/release-note.txt",
        "releaseNotesSha256": hashlib.sha256(notes).hexdigest(),
        "artifacts": [{"abi": "arm64-v8a", "minSdk": int(minimum.group(1)) if minimum else 26,
                       "url": f"{base}/releases/{code}/{filename}", "size": apk.stat().st_size, "sha256": digest(apk)}],
    }
    release["signature"] = key_operation("sign", canonical(release))
    (output / "latest.json").write_text(json.dumps(release, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    verified_manifest((output / "latest.json").read_bytes())
    print(f"已生成发布目录：{output}\n安装包：{filename}\n提交：{commit}")


def publish(args):
    directory = Path(args.release).resolve()
    manifest_bytes = (directory / "latest.json").read_bytes()
    release = verified_manifest(manifest_bytes)
    artifact = release["artifacts"][0]
    apk = directory / f"DSHX-arm64-{release['versionName']}.apk"
    if digest(apk) != artifact["sha256"] or apk.stat().st_size != artifact["size"] or digest(directory / "release-note.txt") != release["releaseNotesSha256"]:
        raise ValueError("发布文件与已签名清单不符。")
    if {p.name for p in directory.iterdir()} != {apk.name, "release-note.txt", "latest.json"}:
        raise ValueError("发布目录包含额外文件。")
    base = artifact["url"].split("/releases/", 1)[0]
    current = latest(base)
    if current and (current["versionCode"] > release["versionCode"] or
                    current["versionCode"] != release["versionCode"] and current["commit"] != release["previousCommit"]):
        raise ValueError("服务器已有其他发布，请重新准备版本，不能覆盖或倒退。")
    import uuid
    staging = REMOTE_ROOT + "/.upload-" + uuid.uuid4().hex
    run("ssh", args.host, shlex.join(["mkdir", "-p", REMOTE_ROOT]))
    subprocess.run(["scp", "-r", str(directory), f"{args.host}:{staging}"], check=True)
    remote = r'''
import fcntl, hashlib, json, os, shutil, sys
from pathlib import Path
root, staging = Path(sys.argv[1]), Path(sys.argv[2])
assert staging.parent == root and staging.name.startswith(".upload-")
with (root.parent / ".publish.lock").open("a") as lock:
    fcntl.flock(lock, fcntl.LOCK_EX)
    data = (staging / "latest.json").read_bytes()
    release = json.loads(data)
    code = release["versionCode"]
    assert isinstance(code, int) and code > 0
    latest = root / "latest.json"
    old = json.loads(latest.read_bytes()) if latest.exists() else None
    if old and old["versionCode"] == code:
        assert latest.read_bytes() == data, "已有同编号的不同版本，拒绝覆盖"
    else:
        assert old is None or old["versionCode"] < code and old["commit"] == release["previousCommit"], "发布基础已变化"
    artifact = release["artifacts"][0]
    for name, expected in [("DSHX-arm64-" + release["versionName"] + ".apk", artifact["sha256"]), ("release-note.txt", release["releaseNotesSha256"])]:
        assert "/" not in name and "\\" not in name
        with (staging / name).open("rb") as source:
            digest = hashlib.sha256()
            for block in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(block)
            assert digest.hexdigest() == expected, "上传文件校验失败"
    versions = root / "releases"
    versions.mkdir(exist_ok=True)
    target = versions / str(code)
    if target.exists():
        assert (target / "latest.json").read_bytes() == data, "版本目录不可变"
        shutil.rmtree(staging)
    else:
        staging.rename(target)
    temporary = root / ".latest.json.new"
    with temporary.open("wb") as output:
        output.write(data); output.flush(); os.fsync(output.fileno())
    temporary.replace(latest)
print("发布完成：", code, release["versionName"])
'''
    result = subprocess.run(["ssh", args.host, shlex.join(["python3", "-", REMOTE_ROOT, staging])],
                            input=remote, text=True, capture_output=True, check=True)
    print(result.stdout.strip())
    remote_release = latest(base)
    if remote_release != release:
        raise ValueError("公网清单与刚发布版本不一致。")
    with urlopen(release["releaseNotesUrl"], timeout=15) as response:
        notes = response.read(128 * 1024 + 1)
    if hashlib.sha256(notes).hexdigest() != release["releaseNotesSha256"]:
        raise ValueError("公网更新说明校验失败。")
    print(f"公网清单和更新说明校验通过：{base}/latest.json")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    command = commands.add_parser("prepare", help="从已推送的干净 APK 准备发布文件")
    command.add_argument("--apk", required=True)
    command.add_argument("--base-url", default=DEFAULT_URL)
    command.add_argument("--output", default=str(ROOT / "build/ota"))
    command.set_defaults(action=prepare)
    command = commands.add_parser("publish", help="上传已准备的版本并切换最新版清单")
    command.add_argument("--release", required=True)
    command.add_argument("--host", default="root@47.119.120.17")
    command.set_defaults(action=publish)
    args = parser.parse_args()
    try:
        args.action(args)
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"发布失败：{error}\n")


if __name__ == "__main__":
    main()
