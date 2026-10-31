# 按软件类型装配

以下是可调整的命令形式。先用实际名称、固定版本和真实入口填写变量；不要把示例变量或假设的命令名直接当成用户目标。命令需要在 DSHX 的终端执行，路径须始终引用用户目录或当前项目。

## Node 应用与命令

已有项目按其锁文件和包管理器安装；独立工具使用自己的目录，不在 `$PREFIX` 或引擎依赖目录执行安装。先查 `package.json` 的 `engines`、`os`、`cpu`、`bin` 与安装脚本；`bin` 才声明真实命令入口，不假设包名就是命令名。

```bash
mkdir -p "$target"
npm install --prefix "$target" --save-exact "$package@$version"
```

按需要选择 npm 或 pnpm，保留生成的包信息与锁文件，避免混用两套锁文件。需要复现安装时使用相应锁文件安装方式，不把未固定版本的 npx/pnpx 临时执行当作持久安装。

可以先用 `--ignore-scripts` 检查下载内容；若程序依赖构建或下载步骤，检查脚本与 Android 支持后，在已有授权范围内补齐必要步骤，并重新验证。跳过脚本后的包管理器成功不代表应用可用。需要 node-gyp、桌面平台二进制或浏览器组件时，先确认实际可用的 Android 构建，不强制忽略平台错误。

通过 `node "$target/node_modules/包内实际入口"` 检查 JavaScript 命令；原生入口则直接检查。需要稳定命令时，生成调用这个实际入口的 shell 文件，避免依赖另一个终端的 PATH。作用域包的目录也要完整保留。

## Python 应用与依赖

优先使用预装 Python。**虚拟环境**由 `python3 -m venv` 创建，拥有独立的第三方包目录；它与基础 Python 共享标准库，不应把它当成另一种系统环境。

```bash
python3 -m venv "$target/venv"
"$target/venv/bin/python" -m pip install "$package==$version"
"$target/venv/bin/python" -m pip check
```

保持显式解释器路径，随后执行真实模块导入、该包的命令或一个最小输入任务；不要只看 pip 输出。命令入口读取安装后的实际可执行文件或模块，不假定发行包名、导入名、命令名相同。项目有锁文件或 requirements 文件时按项目约定安装和固定版本。

纯 Python wheel 通常更容易使用；wheel 是 Python 的预构建分发包，不表示它一定不含原生代码。依赖 C、Rust 等扩展时，核对 Python 版本、Android 支持和所需构建程序。不能用普通 Linux wheel、`--break-system-packages` 或改包元数据来掩盖不兼容，也不在基础运行包中全局安装或升级 pip。

## 新运行时或 Android 原生程序

确实需要另一个解释器、JDK、编译器或独立程序时，查它的官方 Android 构建及维护说明。默认安装在本软件目录中；保留配套动态库、标准库、配置与许可，不只复制一个可执行文件。

- 核对 Android 平台与处理器架构，检查下载包结构和所需最低系统接口。`linux-arm64` 标签本身不能证明 Android 兼容。
- 解压前检查文件路径、链接与目标目录，不能让包覆盖用户目录之外的文件；只向本次暂存目录解包。`.deb` 不是普通 tar 包，设备没有 `dpkg-deb` 时应选择可用的官方归档格式或经过验证的解析方式，不直接运行桌面安装脚本。
- 有私有动态库时，只在该程序的入口中补充其库目录，并保留原来的 `LD_LIBRARY_PATH`；保留宿主的兼容库和证书环境。`PREFIX`、`TERMUX__PREFIX` 仍指向宿主运行包，不改成新软件目录。
- Termux 软件包可能包含固定的 Termux 路径及其他包依赖；下载一个包不等于完整安装。不能在应用私有目录伪造 `/data/data/com.termux`，或直接写入宿主已校验的程序目录。
- 下载后执行最小真实任务；缺少 Android 产物或依赖时明确停止在当前失败步骤，给出需要开发机交叉编译或发布适配包的具体原因。

## 命令入口示例

入口是本技能在用户目录创建的普通 shell 文件。以下两行展示 Node 工具形式，实际文件中应替换名称、版本、包名和入口；Python 可改为虚拟环境解释器和实际模块，原生程序则调用其真实文件。

```sh
#!/system/bin/sh
exec "${PREFIX:?}/bin/node" "$HOME/.local/share/dshx/software/实际名称/实际版本/node_modules/实际包名/实际入口.js" "$@"
```

这里使用宿主提供的 `$PREFIX/bin/node`，不额外下载 Node。生成入口后设置执行权限，并在新终端以绝对路径测试。需要 `activate.sh` 时只设置这个软件需要的变量和 PATH，保留宿主已有值；其作用是让当前终端能够使用新环境，不是宿主后台注册机制。
