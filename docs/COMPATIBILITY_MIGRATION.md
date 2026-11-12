# 兼容层维护原则

MyCinema 的方向已经确定：影院能力由 MyCinema 自有代码实现，官方 WaterFrames 与 WaterMedia 作为未修改的外部运行时。

## 三层结构

1. **影院领域层**：电影库、节目单、影厅、排片、权限、设备拓扑、场景联动与持久化。
2. **官方 API 适配层**：把 MyCinema 的播放和显示命令翻译为官方公开 API 调用。
3. **必要的运行时连接**：仅在没有公开生命周期入口时使用固定版本 Mixin。

## 变更规则

- 新影院能力先进入 `local.cinemamanager`，不得以第三方包名保存。
- 优先使用 NeoForge 事件和官方公开访问器；Mixin 必须有明确、可复现的缺口。
- 不复制、反编译移植或重打包上游实现。
- 不把 WaterFrames / WaterMedia JAR 放进 Git、MyCinema JAR 或发布压缩包。
- 依赖升级必须同时更新版本表、第三方说明、兼容检查和完整运行时证据。
- 旧世界迁移先在副本上测试；不得把不可逆的原地修改作为第一次验证。

## 投影机 ID 合同

- 官方 `waterframes:projector` 保持官方行为，不注入私有样式。
- MyCinema 影院投影机使用 `mycinema:cinema_projector`。
- MyCinema IMAX GT 机柜使用 `mycinema:imax_gt_projector`，并与影院投影机并行注册，而不是共用物品 NBT 样式。
- 历史私有 WaterFrames 中的 `style=cinema|imax_gt` 或 `model_core=true` 状态只作为迁移输入识别；不能假定官方 WaterFrames 会保留这些私有属性。迁移必须先复制世界、列出两台投影机及 TMS 身份，再转换为对应 MyCinema ID 并复核双向绑定、几何和播放。

当前类与行为对应表见 [官方依赖兼容实现](UPSTREAM_COMPATIBILITY.md)，许可边界见 [开源发布审查](OPEN_SOURCE_AUDIT.md)。
