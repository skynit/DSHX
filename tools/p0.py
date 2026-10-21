#!/usr/bin/env python3
"""P0 的只读准备检查；不会安装 APK、操作应用、调用模型或自动通过 Gate。"""

import argparse
import hashlib
import json
import os
import platform
from pathlib import Path
import shutil
import subprocess
import sys
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]


def run(arguments):
    try:
        result = subprocess.run(arguments, capture_output=True, text=True, timeout=20, check=False)
        return {"exit_code": result.returncode, "stdout": result.stdout.strip(), "stderr": result.stderr.strip()}
    except (OSError, subprocess.TimeoutExpired) as error:
        return {"exit_code": None, "error": str(error)}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def devices():
    result = run(["adb", "devices", "-l"])
    if result["exit_code"] != 0:
        return result, []
    rows = []
    for line in result["stdout"].splitlines()[1:]:
        columns = line.split()
        if len(columns) >= 2:
            rows.append((columns[0], columns[1]))
    return result, rows


def inventory():
    adb_result, connected = devices()
    sdk = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or Path.home() / "Android/Sdk")
    java_home = os.environ.get("JAVA_HOME")
    java = str(Path(java_home) / "bin/java") if java_home else shutil.which("java")
    apk = ROOT / "prototype/build/outputs/apk/debug/prototype-debug.apk"
    return {
        "kind": "host-inventory-not-device-gate",
        "observed_at": datetime.now(timezone.utc).isoformat(),
        "system": platform.system(),
        "java": run([java, "-version"]) if java else {"error": "未找到 Java"},
        "sdk": str(sdk),
        "sdk_platforms": sorted(p.name for p in (sdk / "platforms").glob("android-*")),
        "build_tools": sorted(p.name for p in (sdk / "build-tools").glob("*")),
        "adb_status": {key: value for key, value in adb_result.items() if key != "stdout"},
        "devices": [{"device_id_sha256": hashlib.sha256(serial.encode()).hexdigest(), "status": status} for serial, status in connected],
        "prototype_apk": {"bytes": apk.stat().st_size, "sha256": digest(apk)} if apk.exists() else None,
        "gate_status": "not-run",
    }


def check_cases():
    cases = json.loads((ROOT / "p0/cases.json").read_text())
    errors = []
    pending = []
    for name in ("input", "expected"):
        path = (ROOT / cases["T1"][name]).resolve()
        if not path.is_relative_to(ROOT) or not path.is_file():
            errors.append(f"T1 {name} 文件缺失或越界")
        elif digest(path) != cases["T1"][name + "_sha256"]:
            errors.append(f"T1 {name} 内容与固定哈希不一致，须明确登记新样本")
    if len(cases["T2"]) != 3:
        errors.append("T2 必须有三个独立应用任务登记位")
    for case in cases["T2"]:
        fields = ("package", "version", "task", "input", "success_evidence", "rejection_conditions")
        missing = [key for key in fields if not case.get(key)]
        if missing:
            pending.append(case["id"] + " 缺少 " + ", ".join(missing))
    fields = ("package", "version", "dependency_lock", "input", "expected", "review_evidence")
    missing = [key for key in fields if not cases["T3"].get(key)]
    if missing:
        pending.append("T3 缺少 " + ", ".join(missing))
    pending.append("G0 的五名目标用户走查及 G1–G6 必须人工审阅真实证据，本脚本不能自动通过")
    return {"kind": "case-integrity", "errors": errors, "pending": pending, "gate_status": "not-passed"}


def collect(serial):
    result, connected = devices()
    if result["exit_code"] != 0 or (serial, "device") not in connected:
        raise ValueError("指定设备未连接或未授权；没有运行任何设备探测")
    # 参数列表固定，无任意 shell 输入；仅收集系统信息，不读取页面、文件或账号。
    commands = {
        "manufacturer": ["getprop", "ro.product.manufacturer"],
        "model": ["getprop", "ro.product.model"],
        "android": ["getprop", "ro.build.version.release"],
        "api": ["getprop", "ro.build.version.sdk"],
        "abi": ["getprop", "ro.product.cpu.abilist"],
        "page_size": ["getconf", "PAGESIZE"],
        "webview": ["dumpsys", "webviewupdate"],
    }
    return {
        "kind": "device-inventory-not-core-validation",
        "observed_at": datetime.now(timezone.utc).isoformat(),
        "device_id_sha256": hashlib.sha256(serial.encode()).hexdigest(),
        "observations": {name: run(["adb", "-s", serial, "shell", *command]) for name, command in commands.items()},
        "gate_status": "not-run",
        "limitations": ["没有执行 Node/bash/rg/PTY/TLS", "没有验证手机任务、恢复或用户流程"],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("check", help="只检查固定样本与待补实物，不自动通过产品门槛")
    for command in ("inventory", "collect"):
        sub = commands.add_parser(command)
        sub.add_argument("--output", type=Path, required=True, help="必须是尚不存在的本地 JSON 路径")
        if command == "collect":
            sub.add_argument("--serial", required=True, help="明确指定已授权设备，避免操作错误目标")
    args = parser.parse_args()
    try:
        if args.command == "check":
            result = check_cases()
        else:
            if args.output.exists():
                raise ValueError("输出文件已存在；请使用新的文件名，避免覆盖旧证据")
            result = inventory() if args.command == "inventory" else collect(args.serial)
            args.output.parent.mkdir(parents=True, exist_ok=True)
            with args.output.open("x") as stream:
                json.dump(result, stream, ensure_ascii=False, indent=2)
                stream.write("\n")
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 1 if result.get("errors") else 0
    except (ValueError, OSError, KeyError) as error:
        print(str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
