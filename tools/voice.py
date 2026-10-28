#!/usr/bin/env python3
"""Prepare pinned native voice dependencies; large optional models stay outside the APK."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / "core/cache/voice"
ASSETS = ROOT / "app/src/main/assets/voice"
RUNTIME_SHA = "9b2a290b8c7f31bd0aba35abb4628e87fe8d0eb71796a98aa12f3acd089ceaed"
WAKE_SHA = "68447f4fbc67e70eee3a93961f36e81e98f47aef73ce7e7ca00885c6cd3616a6"
LLAMA_COMMIT = "36cbf415f1c72c199e956c00fed0418f22ca53f2"
LLAMA_SHA = "5c76b0b9ec2c948f0355bc99d968f13681341342c75ca32bd562a6a23d6f9929"
PINYIN_COMMIT = "9e5e9bdfe8add694300e0cc93a27b803b68377f9"
PINYIN_SHA = "7365b877d2a60f64d13de522ad882ce928c7b60c4c5d26fe3c89783e72ab1115"


def digest(path):
    with path.open("rb") as handle:
        return hashlib.file_digest(handle, "sha256").hexdigest()


def fetch(url, target, sha, proxy):
    if target.is_file() and digest(target) == sha:
        return target
    temporary = target.with_suffix(target.suffix + ".part")
    if not temporary.is_file() or digest(temporary) != sha:
        command = ["curl", "-fLsS", "--connect-timeout", "15", "--max-time", "900", "--retry", "2"]
        if proxy:
            command += ["--proxy", proxy]
        subprocess.run(command + [url, "-o", str(temporary)], check=True)
    if digest(temporary) != sha:
        raise ValueError("Voice dependency checksum mismatch: " + target.name)
    temporary.replace(target)
    return target


def prepare(proxy):
    CACHE.mkdir(parents=True, exist_ok=True)
    ASSETS.mkdir(parents=True, exist_ok=True)
    runtime = ROOT / "app/libs/sherpa-onnx-1.13.2.aar"
    runtime.parent.mkdir(exist_ok=True)
    fetch("https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.2/sherpa-onnx-static-link-onnxruntime-1.13.2.aar", runtime, RUNTIME_SHA, proxy)
    archive = fetch("https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20.tar.bz2", CACHE / "wake.tar.bz2", WAKE_SHA, proxy)
    wake = ASSETS / "wake"
    wake.mkdir(exist_ok=True)
    names = {
        "encoder.onnx": "encoder-epoch-13-avg-2-chunk-16-left-64.int8.onnx",
        "decoder.onnx": "decoder-epoch-13-avg-2-chunk-16-left-64.onnx",
        "joiner.onnx": "joiner-epoch-13-avg-2-chunk-16-left-64.int8.onnx",
        "tokens.txt": "tokens.txt", "en.phone": "en.phone",
    }
    with tarfile.open(archive) as source:
        for name, original in names.items():
            member = source.getmember("sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20/" + original)
            if not member.isfile():
                raise ValueError("Model archive entry is not a regular file")
            with source.extractfile(member) as data, (wake / name).open("wb") as output:
                shutil.copyfileobj(data, output)
    (wake / "silero_vad.onnx").unlink(missing_ok=True)
    pinyin = fetch("https://raw.githubusercontent.com/mozillazg/pinyin-data/" + PINYIN_COMMIT + "/pinyin.txt", CACHE / "pinyin-data-pinyin.txt", PINYIN_SHA, proxy)
    shutil.copy2(pinyin, wake / "pinyin.txt")
    catalog = {
        "wake": {"version": "20251220-1", "files": [
            {"name": path.name, "bytes": path.stat().st_size, "sha256": digest(path), "asset": "voice/wake/" + path.name}
            for path in sorted(wake.iterdir()) if path.is_file()
        ]},
        "local": {"version": "50968a44", "files": []},
    }
    models = [
        ("local", "unsloth/Qwen3-0.6B-GGUF", "50968a4468ef4233ed78cd7c3de230dd1d61a56b", "Qwen3-0.6B-Q4_K_M.gguf", 396705472, "ac2d97712095a558e31573f62f466a3f9d93990898b0ec79d7c974c1780d524a"),
    ]
    for group, repo, revision, name, size, sha in models:
        tail = f"{repo}/resolve/{revision}/{name}"
        catalog[group]["files"].append({"name": name, "bytes": size, "sha256": sha,
            "url": "https://huggingface.co/" + tail, "mirror": "https://hf-mirror.com/" + tail})
    (ASSETS / "models.json").write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n")
    llama = CACHE / ("llama.cpp-" + LLAMA_COMMIT)
    if not (llama / "include/llama.h").is_file():
        source = fetch("https://codeload.github.com/ggml-org/llama.cpp/tar.gz/" + LLAMA_COMMIT, CACHE / "llama.tar.gz", LLAMA_SHA, proxy)
        with tarfile.open(source) as archive:
            archive.extractall(CACHE, filter="data")
    print("Pinned voice runtime and bundled wake models are ready.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["prepare"])
    parser.add_argument("--proxy", default=None)
    args = parser.parse_args()
    prepare(args.proxy)
