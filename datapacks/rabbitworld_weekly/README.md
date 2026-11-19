# Rabbit World 个人周常

将 ZIP 放入测试世界的 `datapacks/` 并重启。用 `/rabbitquest weekly` 打开周常。此包包含 8 项常驻任务及 3 份轮换工程订单，每周只开放一份订单。玩家分别交付、推进和领奖。RabbitQuest 默认在香港时间周一 04:00 刷新，已完成的奖励可补领到下一周期结束。

- 药剂交付要求物品 ID 与基础药水种类同时匹配。箭使用 `minecraft:weakness`，药水使用 `minecraft:strong_healing`。
- 深暗清剿按监守者受击位置的 `minecraft:deep_dark` 生物群系计数。
- 主城打卡箱体围绕旧目标点 (-24, 80, -1)。在实际主城核对高度与边界后，按需调整 `weekly_city_checkin.json`。
- 工程订单发绿色工具兑换券，战斗类常驻任务发武器工具兑换券；旧特殊奖池使用固定奖励替代。

此包需要支持 `potion` 和 `biome` 条件的 RabbitQuest 本地构建。
