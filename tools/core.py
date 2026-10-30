#!/usr/bin/env python3
"""Build a pinned Android Node + official dsh bundle; no rootfs or install scripts."""
import argparse
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "core"
CACHE = SOURCE / "cache"
ASSETS = ROOT / "app/src/main/assets"


def digest(path):
    with path.open("rb") as handle:
        return hashlib.file_digest(handle, "sha256").hexdigest()


def download(item):
    target = CACHE / (item["sha256"] + ".deb")
    if not target.is_file() or digest(target) != item["sha256"]:
        staging = target.with_suffix(".part")
        # Both domains are Termux's official package endpoints. The pinned hash,
        # rather than the selected endpoint, determines the accepted bytes.
        url = item["url"].replace("packages.termux.dev", "packages-cf.termux.dev")
        subprocess.run(["curl", "-fLsS", "--retry", "3", "--max-time", "240", url, "-o", str(staging)], check=True)
        if digest(staging) != item["sha256"]:
            raise ValueError("Termux package hash mismatch: " + item["url"])
        staging.replace(target)
    return target


def copy_libraries(usr, bundle):
    # Keep the exact names requested by Android's loader. Package symlinks
    # otherwise turn one ICU library into three full copies in the archive.
    system = {"libc.so", "libdl.so", "libm.so", "liblog.so", "libandroid.so"}
    pending = []
    for file in bundle.rglob("*"):
        if file.is_file() and (file.parent == bundle / "bin" or
                               file.is_relative_to(bundle / "libexec") or file.suffix == ".node"):
            with file.open("rb") as handle:
                if handle.read(4) == b"\x7fELF":
                    pending.append(file)
    copied = set()
    while pending:
        file = pending.pop()
        dynamic = subprocess.check_output(["readelf", "-dW", str(file)], text=True)
        for name in re.findall(r"\(NEEDED\).*?\[([^\]]+)\]", dynamic):
            if name in copied or name in system:
                continue
            source = usr / "lib" / name
            if not source.is_file():
                raise ValueError("Missing Android library: " + name)
            target = bundle / "lib" / name
            shutil.copy2(source.resolve(), target)
            copied.add(name)
            pending.append(target)


def copy_git(usr, bundle):
    """安装 Git、传输助手和模板，适配 DSHX 的独立应用目录。"""
    shell = b"/data/data/com.termux/files/usr/bin/sh\0"
    replacement = b"/system/bin/sh\0".ljust(len(shell), b"\0")
    git = usr / "bin/git"
    data = git.read_bytes()
    if data.count(shell) != 1:
        raise ValueError("Git shell path anchor changed")
    (bundle / "bin/git").write_bytes(data.replace(shell, replacement))
    executables = {"bin/git"}
    for source in (usr / "libexec/git-core").rglob("*"):
        if not source.is_file():
            continue
        relative = source.relative_to(usr)
        target = bundle / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        if source.resolve() == git.resolve():
            # 用轻量入口代替指向同一 Git 主程序的大量链接，避免重复打包。
            command = "" if source.name == "git" else " " + source.name.removeprefix("git-")
            target.write_text('#!/system/bin/sh\nexec "$GIT_EXEC_PATH/../../bin/git"' + command + ' "$@"\n')
        else:
            data = source.read_bytes()
            if data.startswith(b"\x7fELF"):
                data = data.replace(shell, replacement)
            elif data.startswith(b"#!" + shell[:-1] + b"\n"):
                data = data.replace(b"#!" + shell[:-1] + b"\n", b"#!/system/bin/sh\n", 1)
            target.write_bytes(data)
        if source.stat().st_mode & 0o111:
            executables.add(relative.as_posix())
    templates = usr / "share/git-core/templates"
    shutil.copytree(templates, bundle / "share/git-core/templates", symlinks=False)
    for source in templates.rglob("*"):
        if source.is_file() and source.stat().st_mode & 0o111:
            relative = source.relative_to(usr)
            data = source.read_bytes().replace(b"#!" + shell[:-1] + b"\n", b"#!/system/bin/sh\n", 1)
            (bundle / relative).write_bytes(data)
            executables.add(relative.as_posix())
    return executables


def build(abi):
    CACHE.mkdir(parents=True, exist_ok=True)
    ASSETS.mkdir(parents=True, exist_ok=True)
    packages = json.loads((SOURCE / "termux.lock.json").read_text())[abi]
    subprocess.run(["npm", "ci", "--omit=dev", "--ignore-scripts", "--no-audit", "--no-fund"], cwd=SOURCE, check=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        debs = list(pool.map(download, packages.values()))
    with tempfile.TemporaryDirectory(prefix="dshx-", dir=CACHE) as temporary:
        temp = Path(temporary)
        unpacked = temp / "packages"
        for deb in debs:
            subprocess.run(["dpkg-deb", "-x", str(deb), str(unpacked)], check=True)
        usr = unpacked / "data/data/com.termux/files/usr"
        for file in usr.rglob("*"):
            if file.is_symlink():
                link = os.readlink(file)
                prefix = "/data/data/com.termux/files/usr/"
                if link.startswith(prefix):
                    file.unlink()
                    file.symlink_to(os.path.relpath(usr / link[len(prefix):], file.parent))
        bundle = temp / "core"
        (bundle / "bin").mkdir(parents=True)
        (bundle / "lib").mkdir()
        executables = {"bin/node", "bin/bash", "bin/rg", "bin/dshx-run"}
        for name in ("node", "bash", "rg"):
            shutil.copy2(usr / "bin" / name, bundle / "bin" / name)
        executables.update(copy_git(usr, bundle))
        cert = bundle / "etc/tls/cert.pem"
        cert.parent.mkdir(parents=True)
        shutil.copy2(usr / "etc/tls/cert.pem", cert)
        for name in ("doc", "licenses", "LICENSES", "terminfo"):
            source = usr / "share" / name
            if source.exists():
                shutil.copytree(source, bundle / "share" / name, symlinks=False)
        # Preserve runtime JS, frontend assets, manifests and licenses. Office
        # conversion is not part of the phone app; no LibreOffice engine ships.
        def exclude(directory, names):
            return [name for name in names if name in (".bin", ".cache", "test", "tests", "__tests__", "prebuilds") or
                    name.endswith((".map", ".d.ts", ".d.cts", ".d.mts", ".pdb", ".node", ".so", ".dll", ".exe", ".dylib")) or
                    name.lower().startswith(("readme.", "changelog.")) or
                    name == "libreoffice-kit" or name.startswith("libreoffice-kit-") or
                    name.startswith(("node-addon-system-linux-", "sherpa-onnx-linux-", "sharp-linux-", "sharp-libvips-linux-"))]
        modules = bundle / "lib/node_modules"
        shutil.copytree(SOURCE / "node_modules", modules, ignore=exclude, symlinks=False)
        # The official WASM build keeps sharp's image pipeline available on
        # Android. Retain its bundled third-party license notice as well.
        sharp_license = bundle / "share/licenses/sharp-wasm32"
        sharp_license.mkdir(parents=True, exist_ok=True)
        shutil.copy2(SOURCE / "node_modules/@img/sharp-wasm32/README.md", sharp_license / "README.md")
        excluded_native = []
        for file in modules.rglob("*"):
            if file.is_file():
                with file.open("rb") as handle:
                    header = handle.read(4)
                if header in (b"\x7fELF", b"\xcf\xfa\xed\xfe", b"\xca\xfe\xba\xbe") or header[:2] == b"MZ":
                    excluded_native.append(file.relative_to(modules).as_posix())
                    file.unlink()
        patches = []

        def patch(path, before, after):
            file = modules / path
            old = file.read_text()
            if old.count(before) != 1:
                raise ValueError("Upstream patch anchor changed: " + path)
            initial = digest(file)
            file.write_text(old.replace(before, after))
            patches.append({"path": path, "before": initial, "after": digest(file)})

        # Keep the single-document format and its strict validation; only route
        # our owned domains into their own directories beside their files.
        patch("@deepseek-ai/dsh-storage-json/lib/index.js",
              '\tconst path = join(root, `${descriptor.name}.json`);',
              '\tconst nested = ["memory", "knowledge"].includes(descriptor.name);\n'
              '\tconst path = nested ? join(root, descriptor.name, "state.json") : join(root, `${descriptor.name}.json`);\n'
              '\tif (nested) await mkdir(dirname(path), { recursive: true, mode: 448 });')

        patch("@deepseek-ai/dsh-sdk-app/cordis.patch.yml",
              '      disabled: !!js "!(process.env.DSH_PRIMARY_RUNTIME ?? process.env.DSH_BUNDLED_PRIMARY_RUNTIME) && !process.env.DSH_BUNDLED_OFFICE_CLI"',
              "      disabled: true")
        # Leave ordinary PDF, image, text and spreadsheet previews available.
        patch("@deepseek-ai/dsh-client-ui-sidebar-documentpreview/lib/client.js",
              "\t\t\tapply$2(ctx, config.office);",
              "\t\t\t// Office conversion is not bundled on Android.")

        # A restarted engine uses a new origin. Seed only the selected session;
        # all execution grants still come from the authenticated native bridge.
        patch("@deepseek-ai/dsh-client-ui-workspace/lib/client.js",
              'selection = (0, _deepseek_ai_dsh_client_store.createSnapshotStore)({}, { persist: { name: "dsh.sessions.current" } });',
              'selection = (() => {\n'
              '\t\t\t\ttry {\n'
              '\t\t\t\tconst url = new URL(window.location.href);\n'
              '\t\t\t\tconst session = url.searchParams.get("dshx-session");\n'
              '\t\t\t\tif (session && /^[A-Za-z0-9._:-]{1,128}$/.test(session)) {\n'
              '\t\t\t\t\tlocalStorage.setItem("dsh.sessions.current", JSON.stringify({ sessionId: session }));\n'
              '\t\t\t\t}\n'
              '\t\t\t\tif (url.searchParams.has("dshx-session")) {\n'
              '\t\t\t\t\turl.searchParams.delete("dshx-session");\n'
              '\t\t\t\t\thistory.replaceState(history.state, "", url);\n'
              '\t\t\t\t}\n'
              '\t\t\t\t} catch { /* Keep the upstream navigation available if storage fails. */ }\n'
              '\t\t\t\treturn (0, _deepseek_ai_dsh_client_store.createSnapshotStore)({}, { persist: { name: "dsh.sessions.current" } });\n'
              '\t\t\t})();')

        patch("@deepseek-ai/dsh-attachment-local/lib/index.js",
              'import { dirname, join, parse, resolve } from "node:path";',
              'import { dirname, join, parse, resolve } from "node:path";\nimport { homeBoundary as androidHomeBoundary } from "../../../../../policy.mjs";')
        patch("@deepseek-ai/dsh-attachment-local/lib/index.js",
              'await ensureDurableDirectory(home, parse(home).root);',
              'await ensureDurableDirectory(home, process.platform === "android" ? androidHomeBoundary(home) : parse(home).root);')

        # The published native helper has no Android build. The fixed Node
        # launcher exposes the same real internal modules, so no dummy binding
        # or private V8 ABI is needed for the official profile resolver.
        patch("node-addon-require-builtin/lib/index.js",
              "const api = createEntryApi(node_path_1.default.resolve(__dirname, '..'));",
              "const api = process.platform === 'android' ? {\n"
              "    requireBuiltin: (id) => require(id),\n"
              "    isAllowedInternalId: (id) => id.startsWith('internal/'),\n"
              "    getBindingInfo: () => ({ backend: 'expose-internals' })\n"
              "} : createEntryApi(node_path_1.default.resolve(__dirname, '..'));")

        patch("@deepseek-ai/node-addon-system/lib/flock.js",
              "export async function tryLockExclusive(fd) {",
              "export async function tryLockExclusive(fd) {\n"
              "    if (process.platform === 'android') {\n"
              "        return createRequire(import.meta.url)('../../../native.node').tryLockExclusive(fd);\n"
              "    }")
        # The native addon lives at the core root. Resolve relative to this
        # package's actual lib/flock.js location, not the current directory.
        flock = modules / "@deepseek-ai/node-addon-system/lib/flock.js"
        flock.write_text(flock.read_text().replace("../../../native.node", "../../../../../native.node"))
        patches[-1]["after"] = digest(flock)
        tools = modules / "@deepseek-ai/dsh-tools/lib/index.js"
        text = tools.read_text()
        anchor = "guardReason(exec) {\n\t\tconst globalReason = this.layers.global.guardReason(exec);"
        if text.count(anchor) != 1:
            raise ValueError("Upstream final tool guard changed")
        patch("@deepseek-ai/dsh-tools/lib/index.js", 'import { Service, symbols } from "@deepseek-ai/cordis";',
              'import { Service, symbols } from "@deepseek-ai/cordis";\nimport { toolReason as androidToolReason, runTool as androidRunTool, toolAvailable as androidToolAvailable } from "../../../../../policy.mjs";')
        patch("@deepseek-ai/dsh-tools/lib/index.js", anchor,
              "guardReason(exec) {\n\t\tif (process.platform === 'android') { const reason = androidToolReason(exec); if (reason !== undefined) return reason; }\n\t\tconst globalReason = this.layers.global.guardReason(exec);")
        patch("@deepseek-ai/dsh-tools/lib/index.js",
              "const returned = await tool.execute(exec.arguments, exec);",
              "const returned = await (process.platform === 'android' ? androidRunTool(exec, () => tool.execute(exec.arguments, exec)) : tool.execute(exec.arguments, exec));")
        # Keep known history names and executable definitions intact. Only the
        # model-facing catalogs omit unavailable capabilities; the final guard
        # remains the authority for direct, nested and scoped executions.
        patch("@deepseek-ai/dsh-tools/lib/index.js",
              "modeFor(scope) {\n\t\tconst layers = this.layers.chainLayers(scope);",
              "modeFor(scope) {\n\t\tif (process.platform === 'android') return 'native';\n\t\tconst layers = this.layers.chainLayers(scope);")
        patch("@deepseek-ai/dsh-tools/lib/index.js",
              "wireSchemas(scope) {\n\t\tconst view = this.view(scope);",
              "wireSchemas(scope) {\n\t\tconst view = this.view(scope);\n\t\tconst definitions = [...view.visible.values()].filter(tool => process.platform !== 'android' || androidToolAvailable(tool.name, scope));")
        patch("@deepseek-ai/dsh-tools/lib/index.js",
              "schemas: [...view.visible.values()].map((definition) => this.schemaOf(definition, false)),",
              "schemas: definitions.map((definition) => this.schemaOf(definition, false)),")
        patch("@deepseek-ai/dsh-tools/lib/index.js",
              "schemas: [...view.visible.values()].map((definition) => this.schemaOf(definition, false)).filter((schema) => schema.name === RUN_CODE_NAME),",
              "schemas: definitions.map((definition) => this.schemaOf(definition, false)).filter((schema) => schema.name === RUN_CODE_NAME),")
        patch("@deepseek-ai/dsh-tools/lib/index.js",
              "schemas(scope) {\n\t\treturn [...this.view(scope).visible.values()].map",
              "schemas(scope) {\n\t\treturn [...this.view(scope).visible.values()].filter(tool => process.platform !== 'android' || androidToolAvailable(tool.name, scope)).map")
        patch("@deepseek-ai/dsh-tools/lib/index.js",
              "sdkSchemas(scope) {\n\t\treturn [...this.view(scope).visible.values()].filter((definition) => definition.name !== RUN_CODE_NAME)",
              "sdkSchemas(scope) {\n\t\treturn [...this.view(scope).visible.values()].filter((definition) => definition.name !== RUN_CODE_NAME && (process.platform !== 'android' || androidToolAvailable(definition.name, scope)))")
        patch("@deepseek-ai/dsh-tool-fs-search/lib/index.js", 'import { existsSync } from "node:fs";',
              'import { existsSync } from "node:fs";\nimport { workspacePath as androidWorkspacePath } from "../../../../../policy.mjs";')
        patch("@deepseek-ai/dsh-tool-fs-search/lib/index.js",
              "function resolveRgPath() {",
              "function resolveRgPath() {\n\tif (process.platform === 'android') return Promise.resolve(join(parse(process.execPath).dir, 'rg'));")
        patch("@deepseek-ai/dsh-tool-fs-search/lib/index.js",
              '\tlet handle;\n\ttry {\n\t\thandle = ctx.subprocess.spawn({',
              '\tlet handle;\n\ttry {\n\t\tif (process.platform === "android") {\n'
              '\t\t\tconst boundary = argv.indexOf("--");\n'
              '\t\t\tconst root = await androidWorkspacePath(boundary < 0 ? "." : argv[boundary + 1], workdir);\n'
              '\t\t\targv = [...(boundary < 0 ? argv : argv.slice(0, boundary)), "--", root];\n'
              '\t\t}\n\t\thandle = ctx.subprocess.spawn({')
        patch("@deepseek-ai/dsh-tool-fs-search/lib/index.js",
              '\t\tthrow new SearchError(`${toolName} could not start its search command (ripgrep launch failed)`, "SEARCH_FAILED", { cause: error });',
              '\t\tif (error.code === "FS_SANDBOX_DENIED") throw new SearchError(error.message, "SEARCH_FAILED", { cause: error });\n'
              '\t\tthrow new SearchError(`${toolName} could not start its search command (ripgrep launch failed)`, "SEARCH_FAILED", { cause: error });')
        patch("@deepseek-ai/dsh-tool-fs/lib/index.js",
              '\tmapError(error, policy) {',
              '\tmapError(error, policy) {\n\t\tif (process.platform === "android") return error;')
        patch("@deepseek-ai/dsh-tool-bash/lib/index.js",
              'const backgroundEnabled = config.enableRunInBackground ?? true;',
              'const backgroundEnabled = process.platform === "android" ? false : config.enableRunInBackground ?? true;')
        # Both upstream command implementations remain available. The PTY-backed
        # implementation owns `bash`; bounded fresh-shell calls have an explicit name.
        patch("@deepseek-ai/dsh-tool-bash/lib/index.js",
              '\t\t\tname: "bash",',
              '\t\t\tname: process.platform === "android" ? "bash_isolated" : "bash",')
        patch("@deepseek-ai/dsh-tool-terminal/lib/index.js",
              'const enableRunInBackground = config.enableRunInBackground ?? true;',
              'const enableRunInBackground = process.platform === "android" ? false : config.enableRunInBackground ?? true;')
        patch("@deepseek-ai/dsh-tool-terminal/lib/index.js",
              'text: "Use a terminal session only when work needs persistent terminal state or interactive stdin; prefer shell/read/write/edit for bounded one-shot operations. Track every terminal session id and close sessions that no longer matter. An inferred_idle or timeout result does not prove the foreground command exited."',
              'text: process.platform === "android" ? "普通命令默认使用持久 bash。bash_isolated 每次新建 shell；terminal_open 创建额外独立持久终端，使用 terminal_send 连续输入，terminal_read 读取输出，terminal_signal 中断，terminal_close 关闭。terminal_list 中 default 为默认 bash 终端，交互程序优先使用额外终端。终端的 inferred_idle、timeout 或 stdin_read 不代表命令成功；仅 bash 的完成标记给出命令退出码。记录终端编号并关闭不再需要的额外终端。" : "Use a terminal session only when work needs persistent terminal state or interactive stdin; prefer shell/read/write/edit for bounded one-shot operations. Track every terminal session id and close sessions that no longer matter. An inferred_idle or timeout result does not prove the foreground command exited."')
        # All Android presets inherit the same default shell and terminal tools.
        # Minimal's private PTY realm would otherwise create a second default.
        patch("@deepseek-ai/dsh-web-app/presets/minimal.patch.yml",
              '          - id: persistent-shell\n            name: cordis:group',
              '          - id: persistent-shell\n            disabled: !!js process.platform === \'android\'\n            name: cordis:group')
        persistent = "@deepseek-ai/dsh-tool-bash-persistent/lib/index.js"
        patch(persistent,
              'import { randomUUID } from "node:crypto";',
              'import { randomUUID } from "node:crypto";\nimport { policyHost } from "../../../../../host.mjs";')
        patch(persistent,
              '\tconst get = (owner, signal) => {\n\t\tconst existing = pending.get(owner);',
              '\tconst get = (owner, signal) => {\n'
              '\t\tconst id = live.get(owner);\n'
              '\t\tif (id !== void 0 && !ctx.terminals.list(owner).some(session => session.sessionId === id)) {\n'
              '\t\t\tlive.delete(owner); pending.delete(owner);\n'
              '\t\t}\n'
              '\t\tconst existing = pending.get(owner);')
        patch(persistent,
              '\t\t\t\t\ttype: config.backendType,\n\t\t\t\t\tcwd',
              '\t\t\t\t\ttype: config.backendType,\n'
              '\t\t\t\t\t...(process.platform === "android" ? { name: "default" } : {}),\n\t\t\t\t\tcwd')
        patch(persistent,
              'function registerPersistentBash(ctx, config) {',
              'async function registerPersistentBash(ctx, config) {')
        patch(persistent,
              '\tctx.tools.register(defineTool({\n\t\tname: "bash",',
              '\tif (process.platform === "android") {\n'
              '\t\tconst warm = async ({ agent, signal }) => {\n'
              '\t\t\tif (await policyHost.mode(agent.session.id) !== "full") return;\n'
              '\t\t\tawait serialized(agent, () => shells.get(agent, signal ?? AbortSignal.timeout(config.timeoutMs)));\n'
              '\t\t};\n'
              '\t\tctx.on("agent/created", warm);\n'
              '\t\tfor (const agent of ctx.agents.list()) await warm({ agent });\n'
              '\t}\n'
              '\tctx.tools.register(defineTool({\n\t\tname: "bash",')
        patch(persistent,
              'const inject = [\n\t"tools",',
              'const inject = [\n\t"agents",\n\t"tools",')
        patch(persistent,
              'function apply(ctx, config) {',
              'async function apply(ctx, config) {')
        patch(persistent,
              '\tregisterPersistentBash(ctx, resolved);',
              '\treturn registerPersistentBash(ctx, resolved);')
        # Android has no supported command sandbox. Use the deployment's Full
        # mode even when an old session recorded a narrower desktop preset.
        patch("@deepseek-ai/dsh-sandbox-policy/lib/index.js",
              'this.defaultMode = config.mode;',
              'this.defaultMode = process.platform === "android" ? "danger-full-access" : config.mode;')
        patch("@deepseek-ai/dsh-sandbox-policy/lib/index.js",
              'mode: request.mode ?? (session === void 0 ? void 0 : this.overrideOf(session)) ?? this.defaultMode,',
              'mode: process.platform === "android" ? "danger-full-access" : request.mode ?? (session === void 0 ? void 0 : this.overrideOf(session)) ?? this.defaultMode,')
        patch("@deepseek-ai/dsh-user-approval/lib/index.js",
              'return this.overrideOf(session) ?? this.config.policy ?? "ask";',
              'return process.platform === "android" ? "never" : this.overrideOf(session) ?? this.config.policy ?? "ask";')
        patch("@deepseek-ai/dsh-permission-presets/lib/index.js",
              'this.presets = config.presets;',
              'this.presets = process.platform === "android" ? { "danger-full-access": config.presets["danger-full-access"] } : config.presets;')
        for indent in ("\t\t\t", "\t\t\t\t"):
            patch("@deepseek-ai/dsh-permission-presets/lib/index.js",
                  '\n' + indent + 'const defaultPreset = config.defaultPreset.get() ?? inferredDefault;',
                  '\n' + indent + 'const defaultPreset = process.platform === "android" ? "danger-full-access" : config.defaultPreset.get() ?? inferredDefault;')
        patch("@deepseek-ai/dsh-permission-presets/lib/index.js",
              '\tpinInitialPermission(session) {\n',
              '\tpinInitialPermission(session) {\n'
              '\t\t\tif (process.platform === "android") { this.set(session, "danger-full-access"); return; }\n')
        runners = list((modules / "@deepseek-ai/dsh-subprocess-local/lib").glob("runner-launch-*.js"))
        if len(runners) != 1:
            raise ValueError("Upstream subprocess runner changed")
        patch(runners[0].relative_to(modules).as_posix(),
              'if (platform === "linux") return new LinuxProcessInspector(arch, internals);',
              'if (platform === "linux" || platform === "android") return new LinuxProcessInspector(arch, internals);')
        patch("@deepseek-ai/dsh-terminal-bash/lib/index.js",
              'const DEFAULT_BASH_SHELL = "/bin/bash";',
              'const DEFAULT_BASH_SHELL = process.platform === "android" ? process.execPath.replace(/node$/, "bash") : "/bin/bash";')
        # Snapshot trusted engine variables once when a PTY starts. Passing them
        # through spawn avoids echoing values into terminal scrollback; later
        # commands keep the shell's own exported environment untouched.
        patch("@deepseek-ai/dsh-terminal-bash/lib/index.js",
              'const inject = [\n\t"terminals",',
              'const inject = [\n\t...(process.platform === "android" ? ["shellEnv"] : []),\n\t"terminals",')
        patch("@deepseek-ai/dsh-terminal-bash/lib/index.js",
              '\t\t\tenv: childEnvironment(spec, this.config.shellDialect),',
              '\t\t\tenv: {\n'
              '\t\t\t\t...(process.platform === "android" ? this.ctx.shellEnv.collect({\n'
              '\t\t\t\t\tagent: spec.owner, name: "terminal_open", arguments: { type: spec.type },\n'
              '\t\t\t\t\tcallId: spec.sessionId, signal: spec.signal ?? AbortSignal.timeout(this.config.timeoutMs)\n'
              '\t\t\t\t}) : {}),\n'
              '\t\t\t\t...childEnvironment(spec, this.config.shellDialect)\n'
              '\t\t\t},')
        patch("@deepseek-ai/dsh-plugin-manager/lib/index.js",
              'if (options?.approvedBuilds !== void 0) {',
              'if (process.platform === "android" && options?.approvedBuilds?.length > 0) throw new Error("Android 插件安装始终禁用构建脚本，请安装已构建的纯 JS/WASM 发布包");\n'
              '\t\t\t\tif (options?.approvedBuilds !== void 0) {')
        patch("@deepseek-ai/dsh-plugin-manager/lib/index.js",
              'const RESTORED_FILES = ["package.json", "pnpm-lock.yaml"];',
              'const RESTORED_FILES = ["package.json", "pnpm-lock.yaml", "cordis.patch.yml"];')
        patch("@deepseek-ai/dsh-plugin-manager/lib/index.js",
              'const before = this.diskState();',
              'const before = this.diskState();\n'
              '\t\t\t\tconst androidFiles = process.platform === "android" && reason !== "remove" ? await this.readRestoredFiles() : void 0;')
        patch("@deepseek-ai/dsh-plugin-manager/lib/index.js",
              '} catch (error) {\n\t\t\t\t\tif (error instanceof InstallCancelledError)',
              '} catch (error) {\n'
              '\t\t\t\t\tif (androidFiles !== void 0) {\n'
              '\t\t\t\t\t\tawait this.restoreFiles(androidFiles);\n'
              '\t\t\t\t\t\ttry { await this.configure(() => this.publishAndReload()); }\n'
              '\t\t\t\t\t\tcatch (restoreError) { error = new Error(`${messageOf(error)}\\n配置已还原，但运行状态恢复失败，请停止后重新启动：${messageOf(restoreError)}`); }\n'
              '\t\t\t\t\t}\n'
              '\t\t\t\t\tif (error instanceof InstallCancelledError)')
        # Wrap the final public assembly so complete scoped personas retain
        # their original behavior while receiving literal Android settings.
        patch("@deepseek-ai/dsh-system-prompt/lib/index.js",
              'import { Service } from "@deepseek-ai/cordis";',
              'import { Service } from "@deepseek-ai/cordis";\n'
              'import { personaAssembly } from "../../../../../assistant.mjs";')
        patch("@deepseek-ai/dsh-system-prompt/lib/index.js",
              '\tasync assemble(context = {}) {',
              '\tasync assemble(context = {}) {\n'
              '\t\tconst assembly = await this.androidAssemble(context);\n'
              '\t\treturn personaAssembly(assembly);\n'
              '\t}\n'
              '\tasync androidAssemble(context = {}) {')
        # Selection and preparation do not count as model dispatch. Fixed built-in
        # transports own starts; opaque adapters explicitly record execution attempts.
        patch('@deepseek-ai/dsh-llm/lib/index.js',
              'import { createRequire } from "node:module";',
              'import { createRequire } from "node:module";\nimport { usageStream, modelTransport } from "../../../../../assistant.mjs";')
        patch('@deepseek-ai/dsh-llm/lib/index.js',
              '\t\tasync *adapterStream(options, prepared) {',
              '\t\tasync *adapterStream(options, prepared) {\n\t\t\tconst accounting = { ...options, purpose: options.purpose ?? (isAgentLoopRequest(options) ? "conversation" : "other") };')
        patch('@deepseek-ai/dsh-llm/lib/index.js',
              '\t\t\t\titerator = dispatch(this.forAdapter(projectedOptions, adapter))[Symbol.asyncIterator]();',
              '\t\t\t\titerator = usageStream(accounting, () => dispatch(this.forAdapter(projectedOptions, adapter)), void 0, adapter[modelTransport] === true)[Symbol.asyncIterator]();')
        patch('@deepseek-ai/dsh-llm-deepseek/lib/index.js',
              'var DeepSeekAdapter = class extends LlmAdapter {',
              'var DeepSeekAdapter = class extends LlmAdapter {\n\t[modelTransport] = true;')
        patch('@deepseek-ai/dsh-llm-deepseek/lib/index.js',
              '//#region lib/types/translate.js',
              'import { modelTransport, modelFetch, reportProviderUsage } from "../../../../../assistant.mjs";\n//#region lib/types/translate.js')
        patch('@deepseek-ai/dsh-llm-deepseek/lib/index.js',
              '\tconst usage = {\n\t\tinputTokens: 0,\n\t\toutputTokens: 0\n\t};',
              '\tconst usage = {};')
        patch('@deepseek-ai/dsh-llm-deepseek/lib/index.js',
              '\t\t\tupdateUsage(usage, object(event.message).usage);',
              '\t\t\tupdateUsage(usage, object(event.message).usage);\n\t\t\treportProviderUsage({ ...usage });')
        patch('@deepseek-ai/dsh-llm-deepseek/lib/index.js',
              '\t\t\tif (event.usage !== void 0) updateUsage(usage, event.usage);',
              '\t\t\tif (event.usage !== void 0) {\n\t\t\t\tupdateUsage(usage, event.usage);\n\t\t\t\treportProviderUsage({ ...usage }, object(event.delta).stop_reason != null && Number.isSafeInteger(object(event.usage).output_tokens) && object(event.usage).output_tokens >= 0);\n\t\t\t}')
        patch('@deepseek-ai/dsh-llm-deepseek/lib/index.js',
              '\t\t\tusage.totalTokens = usage.inputTokens + usage.outputTokens + (usage.cacheReadTokens ?? 0) + (usage.cacheWriteTokens ?? 0);',
              '\t\t\t// Keep unreported totals unknown; earlier snapshots are already durable.')
        patch('@deepseek-ai/dsh-llm-deepseek/lib/index.js',
              'const response = await fetch(`${messagesApiRoot(connection.baseURL)}/messages`, {',
              'const response = await modelFetch(`${messagesApiRoot(connection.baseURL)}/messages`, {')
        patch('@deepseek-ai/dsh-llm-deepseek/lib/index.js',
              '\t\t\tyield {\n\t\t\t\ttype: "usage",\n\t\t\t\tusage\n\t\t\t};',
              '\t\t\tif (usage.inputTokens !== void 0 && usage.outputTokens !== void 0) yield {\n\t\t\t\ttype: "usage",\n\t\t\t\tusage: { ...usage }\n\t\t\t};')
        patch('@deepseek-ai/dsh-llm-pi-ai/lib/index.js',
              '//#region lib/types/stream.js',
              'import { modelTransport, piOptions, providerUsage } from "../../../../../assistant.mjs";\n//#region lib/types/stream.js')
        patch('@deepseek-ai/dsh-llm-pi-ai/lib/index.js',
              'var PiAiAdapter = class extends LlmAdapter {',
              'var PiAiAdapter = class extends LlmAdapter {\n\t[modelTransport] = true;')
        patch('@deepseek-ai/dsh-llm-pi-ai/lib/index.js',
              '\t\t\t\t\t...profileOptions(profile, reasoning, apiKey),',
              '\t\t\t\t\t...profileOptions(profile, reasoning, apiKey),\n\t\t\t\t\t...piOptions(model),')
        patch('@deepseek-ai/dsh-llm-pi-ai/lib/index.js',
              "/**\n* Map pi-ai usage (reasoning folded into output by pi-ai).\n* @param usage - cumulative usage from the terminal pi-ai event.\n* @returns harness counts with pi-ai's exact total; cache fields appear only\n*   when non-zero (pi-ai reports zeros, not absence).\n*/\nfunction mapUsage(usage) {\n\treturn {\n\t\tinputTokens: usage.input,\n\t\toutputTokens: usage.output,\n\t\ttotalTokens: usage.totalTokens,\n\t\t...usage.cacheRead > 0 ? { cacheReadTokens: usage.cacheRead } : {},\n\t\t...usage.cacheWrite > 0 ? { cacheWriteTokens: usage.cacheWrite } : {}\n\t};\n}\n",
              '// Usage provenance comes from protocol callbacks before pi-ai synthesizes zeros.\n')
        patch('@deepseek-ai/dsh-llm-pi-ai/lib/index.js',
              '\t\t\tyield {\n\t\t\t\ttype: "usage",\n\t\t\t\tusage: mapUsage(event.message.usage)\n\t\t\t};',
              '\t\t\tif (providerUsage() !== void 0) yield {\n\t\t\t\ttype: "usage",\n\t\t\t\tusage: providerUsage()\n\t\t\t};')
        patch('@deepseek-ai/dsh-llm-pi-ai/lib/index.js',
              '\t\t\tyield {\n\t\t\t\ttype: "usage",\n\t\t\t\tusage: mapUsage(event.error.usage)\n\t\t\t};',
              '\t\t\tif (providerUsage() !== void 0) yield {\n\t\t\t\ttype: "usage",\n\t\t\t\tusage: providerUsage()\n\t\t\t};')
        for filename in ("start.mjs", "plugins.mjs", "assistant.mjs", "hosting.mjs", "specimen.mjs", "specimen-store.mjs", "background.mjs", "apps.mjs", "compat.mjs", "guard.mjs", "policy.mjs", "files.mjs", "host.mjs", "phone.mjs", "voice.mjs", "memory.mjs", "memory-store.mjs", "memory-extract.mjs", "read-budget.mjs", "knowledge.mjs", "knowledge-store.mjs", "knowledge-search.mjs", "knowledge-model.mjs", "local-route.mjs", "local-routes.json", "recover.mjs", "android.patch.yml"):
            shutil.copy2(SOURCE / filename, bundle / filename)
        ndk = Path(os.environ.get("ANDROID_NDK_HOME", "/home/skynit/Android/Sdk/ndk/28.2.13676358"))
        compiler = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin" / (
            "aarch64-linux-android26-clang" if abi == "arm64-v8a" else "x86_64-linux-android26-clang")
        subprocess.run([str(compiler), "-shared", "-fPIC", "-O2", "-Wl,-z,max-page-size=16384",
                        "-I/usr/include/node", str(SOURCE / "native.c"), "-o", str(bundle / "native.node")], check=True)
        subprocess.run([str(compiler), "-O2", "-Wl,-z,max-page-size=16384", str(SOURCE / "launcher.c"),
                        "-o", str(bundle / "bin/dshx-run")], check=True)
        # Build the exact node-pty source from npm's integrity-locked dependency.
        # It uses stable N-API; Android provides forkpty in libc, with no libutil.
        pty = modules / "node-pty/prebuilds" / ("android-arm64" if abi == "arm64-v8a" else "android-x64") / "pty.node"
        pty.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run([str(compiler) + "++", "-shared", "-fPIC", "-O2", "-std=c++17",
                        "-fexceptions", "-DNAPI_CPP_EXCEPTIONS", "-DNAPI_VERSION=8",
                        "-Wl,-z,max-page-size=16384", "-I/usr/include/node",
                        "-I" + str(SOURCE / "node_modules/node-addon-api"),
                        str(SOURCE / "node_modules/node-pty/src/unix/pty.cc"), "-o", str(pty)], check=True)
        copy_libraries(usr, bundle)
        # Node addons are at the core root, outside npm's namespace layout.
        # ELF inventory catches accidentally retained Linux executables too.
        elf = []
        for file in bundle.rglob("*"):
            if file.is_file():
                with file.open("rb") as handle:
                    if handle.read(4) == b"\x7fELF":
                        relative = file.relative_to(bundle).as_posix()
                        if relative not in executables | {"native.node"} and not relative.startswith("lib/"):
                            raise ValueError("Unexpected native executable: " + relative)
                        info = subprocess.check_output(["readelf", "-lW", str(file)], text=True)
                        loads = [line for line in info.splitlines() if line.strip().startswith("LOAD")]
                        if not loads or any(int(line.split()[-1], 16) < 16384 for line in loads):
                            raise ValueError("ELF is not aligned for 16 KB pages: " + relative)
                        elf.append(relative)
        files = {}
        archive = ASSETS / "core.zip"
        with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as output:
            for file in sorted(bundle.rglob("*")):
                if not file.is_file():
                    continue
                path = file.relative_to(bundle).as_posix()
                executable = path in executables
                files[path] = {"size": file.stat().st_size, "sha256": digest(file), "executable": executable}
                entry = zipfile.ZipInfo(path, date_time=(2026, 10, 21, 0, 0, 0))
                entry.external_attr = (0o100700 if executable else 0o100600) << 16
                entry.compress_type = zipfile.ZIP_DEFLATED
                with file.open("rb") as source, output.open(entry, "w") as target:
                    shutil.copyfileobj(source, target)
        manifest = {
            "format": 1, "version": "dsh-0.2.1-rc.2-android.1-" + abi,
            "abi": abi, "sha256": digest(archive), "archiveSize": archive.stat().st_size,
            "dsh": "0.2.1-rc.2", "node": packages["nodejs-lts"]["version"],
            "tools": {name: packages[name]["version"] for name in ("bash", "ripgrep", "git")},
            "nodePty": json.loads((SOURCE / "node_modules/node-pty/package.json").read_text())["version"],
            "pnpm": json.loads((SOURCE / "node_modules/pnpm/package.json").read_text())["version"],
            "npm": json.loads((SOURCE / "node_modules/npm/package.json").read_text())["version"],
            "npmLockSha256": digest(SOURCE / "package-lock.json"),
            "termuxLockSha256": digest(SOURCE / "termux.lock.json"),
            "patches": patches, "elf16k": elf, "excludedNative": excluded_native, "files": files,
        }
        (ASSETS / "core.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
        info = {key: manifest[key] for key in ("dsh", "node", "abi")}
        info["unpackedSize"] = sum(item["size"] for item in files.values())
        (ASSETS / "core-info.json").write_text(json.dumps(info, ensure_ascii=False, indent=2) + "\n")
        # Retain each ABI's archive locally, without putting build outputs in Git.
        shutil.copy2(archive, CACHE / (abi + ".zip"))
        shutil.copy2(ASSETS / "core.json", CACHE / (abi + ".json"))
        shutil.copy2(ASSETS / "core-info.json", CACHE / (abi + "-info.json"))
        print(json.dumps({key: value for key, value in manifest.items() if key != "files"}, ensure_ascii=False, indent=2))
        print("files:", len(files), "unpacked bytes:", sum(item["size"] for item in files.values()))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["build"])
    parser.add_argument("--abi", choices=["arm64-v8a", "x86_64"], required=True)
    args = parser.parse_args()
    build(args.abi)
