"""为独立 Android 应用安装命令行环境，不运行软件包安装脚本。"""
from pathlib import Path
import shutil


PACKAGES = (
    "openssh", "curl", "jq", "sqlite", "python", "python-pip",
    "python-ensurepip-wheels", "rsync", "coreutils", "findutils",
    "diffutils", "patch", "tar", "gzip", "zip", "unzip", "termux-exec",
)


def install(usr: Path, bundle: Path):
    executables = set()

    def script(name, body):
        (bundle / "bin" / name).write_text('#!/system/bin/sh\nbin=${0%/*}\n' + body + '\n')
        executables.add("bin/" + name)

    def binary(name, wrapped=False):
        relative = ("libexec/dshx/" if wrapped else "bin/") + name
        target = bundle / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(usr / "bin" / name, target)
        shell = b"/data/data/com.termux/files/usr/bin/sh\0"
        content = target.read_bytes()
        if shell in content:
            target.write_bytes(content.replace(shell, b"/system/bin/sh\0".ljust(len(shell), b"\0")))
        executables.add(relative)

    # coreutils 的各命令原本指向同一个程序；使用小入口避免重复打包。
    binary("coreutils")
    for source in sorted((usr / "bin").iterdir()):
        if source.name != "coreutils" and source.resolve() == (usr / "bin/coreutils").resolve():
            script(source.name, f'exec "$bin/coreutils" --coreutils-prog={source.name} "$@"')
    for name in ("curl", "jq", "sqlite3", "find", "xargs", "diff", "diff3", "cmp", "sdiff",
                 "patch", "tar", "gzip", "zip", "unzip", "zipinfo"):
        binary(name)
    script("gunzip", 'exec "$bin/gzip" -d "$@"')
    script("zcat", 'exec "$bin/gzip" -cd "$@"')
    script("sh", 'exec "$bin/bash" "$@"')

    for name in ("ssh", "scp", "sftp", "rsync", "ssh-keygen", "ssh-agent"):
        binary(name, wrapped=True)
    config = bundle / "etc/ssh/ssh_config"
    config.parent.mkdir(parents=True, exist_ok=True)
    config.write_text('Include ${HOME}/.ssh/config\nHost *\n'
                      '    UserKnownHostsFile ${HOME}/.ssh/known_hosts\n'
                      + ''.join(f'    IdentityFile ${{HOME}}/.ssh/id_{kind}\n' for kind in ("ed25519", "ecdsa", "rsa")))
    script("ssh", 'mkdir -p -m 700 "$HOME/.ssh" || exit\n'
           'exec "$bin/../libexec/dshx/ssh" -F "$bin/../etc/ssh/ssh_config" "$@"')
    for name in ("scp", "sftp"):
        script(name, f'exec "$bin/../libexec/dshx/{name}" -S "$bin/ssh" "$@"')
    script("rsync", 'export RSYNC_RSH="${RSYNC_RSH:-$bin/ssh}"\n'
           'exec "$bin/../libexec/dshx/rsync" "$@"')
    script("ssh-agent", 'exec "$bin/../libexec/dshx/ssh-agent" -a "${TMPDIR:?}/ssh-agent-$$" "$@"')
    # 仅为创建密钥的常用选项补默认文件；其他操作完整交给原程序。
    script("ssh-keygen", 'kind=ed25519\ndefault_file=true\noption=\n'
           'for value in "$@"; do\n'
           '    if [ -n "$option" ]; then\n'
           '        if [ "$option" = -t ]; then kind=$value; fi\n'
           '        option=\n    else\n'
           '        case "$value" in\n'
           '            -t|-b|-C|-N|-a|-w) option=$value ;;\n'
           '            -q) ;;\n'
           '            *) default_file=false ;;\n'
           '        esac\n    fi\ndone\n'
           'if [ "$default_file" = true ]; then\n'
           '    mkdir -p "$HOME/.ssh" || exit\n'
           '    set -- -f "$HOME/.ssh/id_$kind" "$@"\nfi\n'
           'exec "$bin/../libexec/dshx/ssh-keygen" "$@"')
    for name in ("ssh-keyscan", "ssh-add"):
        binary(name)

    python = next((usr / "lib").glob("python3.*"))
    version = python.name.removeprefix("python")
    shutil.copytree(python, bundle / "lib" / python.name, symlinks=False,
                    ignore=shutil.ignore_patterns("__pycache__", "*.pyc", "*.o", "*.a", "test", "tests"))
    shutil.copy2(usr / "bin" / ("python" + version), bundle / "bin/python3")
    executables.add("bin/python3")
    for name in ("python", "python" + version):
        script(name, 'exec "$bin/python3" "$@"')
    for name in ("pip", "pip3", "pip" + version):
        script(name, 'exec "$bin/python3" -m pip "$@"')
    for source in (usr / "lib").glob("libtermux-exec*.so"):
        shutil.copy2(source.resolve(), bundle / "lib" / source.name)
    # stdbuf 会在主程序旁查找辅助库，避开写死的 Termux libexec 路径。
    shutil.copy2(usr / "libexec/coreutils/libstdbuf.so", bundle / "bin/libstdbuf.so")
    return executables
