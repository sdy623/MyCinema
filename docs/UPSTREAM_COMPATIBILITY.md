# 官方依赖兼容实现

## 目标

MyCinema 使用未修改的官方 WaterFrames、WaterMedia 与 WaterMedia Binaries，同时由自己的代码实现完整影院行为。兼容层不保存第三方分支、补丁 JAR或复制的上游类。

## MyCinema 自有实现

| 能力 | 主要实现位置 |
|---|---|
| 影片来源、电影库、节目片段与节目单 | `local.cinemamanager.media`、`catalog`、`library`、`theater` |
| TMS 终端、影厅登记与权限 | `local.cinemamanager.theater`、`policy`、`ManagerTile` |
| 双向放映机组、投影样式与断链保护 | `local.cinemamanager.projection` |
| 影院投影机与 IMAX GT 的独立注册、原创机柜资源和模型驱动碰撞 | `CinemaManager`、`local.cinemamanager.projection`、`assets/mycinema` |
| 单时钟同步、暂停、跳转、停止与重连 | `CinemaTerminalTile`、`FilmPlayback` |
| 镜像显示与显示组 | `local.cinemamanager.mirror`、`client.CinemaMirrorFrames` |
| 音频来源与影院声场 | `local.cinemamanager.audio` |
| 排片、自动开始/结束和场景输出 | `local.cinemamanager.automation`、`ManagerLifecycle` |
| 服务器权威网络操作 | `local.cinemamanager.network`、`ManagerNetwork` |
| 经理界面、节目单、海报与信息屏 | `local.cinemamanager.client`、`panels` |

所有持久化标识、设备关系、节目状态和授权记录都属于 MyCinema。WaterFrames 只提供官方显示设备与渲染边界，WaterMedia 只提供官方媒体播放 API。

## 窄范围运行时连接

`mycinema.mixins.json` 只启用以下固定用途：

1. 放置显示设备时写入 MyCinema 的所有者和影厅初始化状态；
2. 对显示数据更新进行服务器侧范围和授权检查；
3. 让 MyCinema 镜像状态参与官方显示生命周期；
4. 对显示网络操作做服务器权威保护；
5. 在 WaterMedia 3.0.0 接收“跳转后暂停”的服务器状态时先追上权威画面，再在下一次跟随更新中暂停，避免客户端停在旧位置。

投影机碰撞连接只在方块属于 `mycinema:cinema_projector` 或 `mycinema:imax_gt_projector` 时生效，用来让官方 `DisplayBlock` 承载 MyCinema 自有模型的精确锚点形状；它不改变官方 `waterframes:projector`。

Mixin 不拥有电影库、节目单、排片、终端、投影组或权限模型，也不复制或替换 WaterMedia 解码器。暂停同步连接点只调整官方播放器现有的“跳转、再暂停”调用顺序，并由真实 H.264/AAC 客户端测试覆盖。上游目标变化会在启动或测试阶段明确失败。

## 固定兼容版本

| 组件 | 固定版本 | Modrinth 版本 ID |
|---|---:|---|
| WaterFrames | `3.0.0-alpha.1` | `3q7i6sG3` |
| WaterMedia | `3.0.0` | `7aLfBD61` |
| WaterMedia Binaries | `3.0.0` | `IvgVb1Pv` |

Gradle 会自动下载这些官方依赖；MyCinema 发布物不会复制它们。升级任何一个版本都必须重新跑完整 `verifyRelease`，不能仅修改版本号后直接发布。

## 验证范围

兼容实现由四条运行时证据链共同覆盖：

- 48 个 GameTest 类，共 241 个运行时用例；
- 两次启动的专用服务器，验证排片、设备拓扑、授权、持久化和正常关闭；
- 干净客户端世界，验证五页管理界面、保存、退出、重连和资源释放；
- 本地生成的 H.264/AAC 影片，验证视频纹理、音频设备、播放推进、暂停稳定、跳转、双放映机共享、停止及解码线程清零。

单一入口为 `gradlew.bat verifyRelease`。
