# 网页共享入口组件

`daisyui-5.8.2/` 中的 `button.css`、`badge.css`、`list.css` 和 MIT 许可证原样取自 npm 包 `daisyui@5.8.2`。更新时用 `npm pack daisyui@目标版本 --ignore-scripts`，从包内的 `components/` 复制这三份样式，从包根复制 `LICENSE`。同步更新入口页的样式路径和服务端的资源清单。

组件的配色和尺寸在 `portal.css` 中配置。无需从外网加载脚本、字体或样式；共享网页所在的独立来源也不加载这套组件。
