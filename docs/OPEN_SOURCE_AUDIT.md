# MyCinema 开源发布审查

## 结论

MyCinema 自有源代码可以按 Apache License 2.0 发布，但完整运行环境属于**混合许可、非商业用途受限**的组合。发布包只能包含 MyCinema 自有代码与已确认权利的资源；WaterFrames、WaterMedia 和 WaterMedia Binaries 必须由玩家或启动器从各自官方渠道取得。WaterUI 由指定的官方 WaterFrames 文件提供，不进入 MyCinema 发布包。

## 许可与分发边界

| 组件 | 本仓库中的角色 | 发布规则 |
|---|---|---|
| MyCinema | 影院、电影库、节目单、排片、TMS、权限、灯光、门、座位及兼容层 | Apache-2.0；范围见 `LICENSE` 与 `NOTICE` |
| WaterFrames / WaterUI | 外部显示与界面运行时；WaterUI 位于官方 WaterFrames 文件中 | All Rights Reserved；不修改、不嵌入、不重打包、不随 MyCinema 再分发 |
| WaterMedia | 外部媒体 API | DPS Non-Commercial；只作为外部依赖使用，不嵌入、不随 MyCinema 再分发 |
| WaterMedia Binaries | 客户端音视频解码运行时 | 单独安装；不放入本仓库或 MyCinema JAR |
| Ponder / Flywheel | 客户端交互式教程与其渲染运行时 | MIT；从官方 Maven 解析，不嵌入、不重打包进 MyCinema JAR；专用服务器不要求安装 |
| Minecraft / NeoForge | 游戏与加载器 | 遵守各自条款；不分发游戏文件 |

因此，仓库可以准确称为“MyCinema 源代码开源”，不能把所需的完整运行栈描述为全部开源，也不能把 Apache-2.0 延伸到第三方组件。

## 当前实现符合的边界

- 构建只解析三个固定的官方 Modrinth Maven 坐标。
- Ponder 固定为 `1.0.87+mc1.21.1`，其 Flywheel `1.0.4` 运行时通过官方 Creators of Create Maven 传递解析。
- 仓库不追踪第三方 JAR、原生库或编译后的第三方类。
- MyCinema JAR 不含嵌套 JAR，也不含 `me.srrapero720.*`、`org.watermedia.*`、Ponder、Flywheel、Iris 或 Sodium 的类。
- 影院领域逻辑、持久化、网络权限、设备拓扑、自动化和界面均由 `local.cinemamanager` 命名空间拥有。
- Mixin 只连接官方版本已有的放置、显示数据、镜像、网络和暂停时钟生命周期点；没有复制第三方实现或解码器。
- 固定版本不匹配时快速失败，不静默使用未验证的上游行为。

## 资源与署名

MyCinema、KUMO 座椅模型与对应原创纹理、影院投影机和 IMAX GT 机柜模型及纹理、项目图标和横幅由 `sdy623` 创建或控制，并授权纳入本次发布。投影机资源从用户控制的历史开发包恢复后已迁入 `assets/mycinema` 命名空间；没有把历史私有 WaterFrames JAR、类或第三方命名空间资源装入发布物。第三方商标、游戏资产和依赖项目不因本仓库许可证而获得再许可。

发布前仍应把任何后来新增的模型、纹理、声音、字体、翻译或生成资源加入来源记录；来源不明的资源不得进入发布包。

## 自动合规门

`gradlew.bat verifyRelease` 的最后一步会构建 JAR，并执行 `tools/check_release_compliance.py`。该检查拒绝：

- 除 Gradle Wrapper 外的仓库内 JAR；
- DLL、SO、DYLIB、EXE、CLASS 等编译或原生文件；
- 本地 `libs/`、文件依赖或非官方依赖坐标；
- 复制到第三方命名空间的源码；
- MyCinema JAR 内的嵌套依赖或第三方类。

发布页必须把固定版本的 WaterFrames、WaterMedia 与 WaterMedia Binaries 标为必需外部依赖；WaterUI 随指定 WaterFrames 官方文件提供。发布说明同时明确完整运行环境的非商业限制。
