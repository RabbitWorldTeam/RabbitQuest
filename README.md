# RabbitQuest

为 Minecraft 服务器提供兑换商店、周常、限时活动和玩家任务统计。

**Minecraft 1.21.1 · NeoForge 21.1.265+（21.1.x） · Java 21 · 客户端与服务端均需安装**

[发布页](https://github.com/RabbitWorldTeam/RabbitQuest/releases) · [更新日志](CHANGELOG.md) · [问题反馈](https://github.com/RabbitWorldTeam/RabbitQuest/issues)

## 主要功能

- **兔兔任务书** · 在同一界面浏览商店、周常和活动，查看并领取待领奖励。
- **兑换商店** · 按分类浏览与搜索，支持限购、共享库存和管理员编辑。客户端安装 Just Enough Characters 后可按拼音查找商品标题。
- **周常与工程订单** · 支持轮换订单、分批交付，以及击杀、合成、钓鱼、成熟采收和徒步等任务目标。
- **限时活动** · 支持报名、并行路线、前置步骤、点击完成、阶段奖励和通关奖励。
- **任务兔子箱** · 管理员放置后可在游戏内绑定开箱目标。
- **配置与存档** · 通过世界数据包配置任务，支持热重载、行为统计和交易恢复。

## 安装

将 `rabbitquest-1.21.1-neoforge-1.0.0.jar` 放入客户端与服务端的 `mods/`。RabbitQuest 可独立安装。计划从 RabbitCore 的旧任务系统切换时，先结清旧系统待领奖励并备份已有世界和任务配置。RabbitQuest 的任务与统计从启用时开始记录。

## 常用入口

| 入口 | 用途 |
| --- | --- |
| 兔兔任务书 | 右键打开任务书，可切换商店、周常和限时活动 |
| `/rabbitquest` | 打开商店 |
| `/rabbitquest weekly` | 打开周常和工程订单 |
| `/rabbitquest event` | 查看限时活动列表 |
| `/rabbitquest stats` | 查看自己的行为统计 |
| `/rabbitquest reload` | 管理员校验并热重载任务数据包 |

活动配置可以参考[验收数据包](test-datapacks/event-flow)。将自己的数据包放入世界存档的 `datapacks/`，由服务端加载。

## 从源码构建

`./gradlew test build`，Windows 使用 `.gradlew.bat test build`。JAR 输出到 `build/libs/`。GitHub Actions 的 **Build RabbitQuest** 会验证主分支和 PR；**Release** 从默认分支构建并按更新日志发布正式版本。

All Rights Reserved，见[模组元数据](src/main/resources/META-INF/neoforge.mods.toml)。
