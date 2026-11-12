# MyCinema 发布候选检查表

## 仓库与许可

- [x] 发布分支从新的根提交开始，不携带不应公开的二进制历史。
- [x] 作者和原创资源署名统一为 `sdy623`。
- [x] MyCinema 源代码使用 Apache-2.0，并提供 `LICENSE`、`NOTICE` 和第三方说明。
- [x] WaterFrames、WaterMedia 与 WaterMedia Binaries 仅作为外部依赖；WaterUI 由官方 WaterFrames 文件提供，不进入 MyCinema JAR。
- [x] 固定官方版本和 Modrinth Maven 坐标。
- [x] Ponder `1.0.87+mc1.21.1` 与 Flywheel `1.0.4` 从官方 Maven 解析，不进入 MyCinema JAR。
- [x] 仓库和最终 JAR 均由自动合规检查覆盖。
- [x] 发布说明明确完整运行环境为混合许可、非商业用途受限。

## 工程实现

- [x] 电影库、节目单、TMS、投影组、镜像、音频、权限、自动化和网络状态由 MyCinema 自有代码实现。
- [x] 只保留固定版本、窄范围、服务器权威的 Mixin 连接点。
- [x] 使用官方依赖完成编译，不依赖本地 `libs/`。
- [x] 提供单命令 `verifyRelease`。
- [x] 48 个 GameTest 类由清单锁定；实际运行必须完成 241 个用例。
- [x] 客户端测试覆盖五页界面、保存、退出、重连与资源释放。
- [x] 真实 H.264/AAC 电影测试覆盖视频、音频、播放、暂停、跳转、双放映机同步和停止。
- [x] 客户端实际加载两个独立投影机 ID、TMS 终端与全部 MyCinema 美术资源，并产出可辨认、地板完整的 IMAX GT 机柜截图。
- [x] 干净客户端已实际编译 7 个 Ponder 场景、打开馆长终端教程并产出 `mycinema-qa-ponder-manager.png`；新一轮 `verifyRelease` 已通过。
- [x] 专用服务器测试覆盖两次启动、持久化、排片、授权与正常关闭。

## 打包

- [x] 最终 JAR 不包含嵌套依赖或第三方类。
- [x] README 说明影院用途、安装、依赖、容量、使用流程、验证和许可。
- [x] 公开分支整理为一个根提交，不写入内部迭代编号或旧构建说明。
- [x] 源代码压缩包从已验证的提交生成。
- [x] 公开目标仓库确认为 `https://github.com/sdy623/MyCinema`，首发标签为 `v0.1.0`。

## 最终命令

```text
gradlew.bat verifyRelease
```

这条命令必须在整理最终提交前完整通过。GitHub 发布页还必须声明必需依赖，并提醒服务器管理者只播放自己有权使用的媒体。
