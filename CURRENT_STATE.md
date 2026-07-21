# 当前项目状态

检查日期：2026-07-21

本文件记录代码实现的实际状态，优先级高于历史设计文档 `PLAN.md`。`PLAN.md` 保留了已经废弃的会话认证、方法黑白名单、BE/Capability 方法调用等初始设计，不能作为当前 API 或安全行为的依据。

## 已实现

- Forge 1.20.1（47.4.20）、Java 17 工程；mod id 为 `web_monitor_interface`，展示名称为 `Web Monitor Interface`。
- 嵌入式 Netty HTTP/WebSocket 服务，默认绑定 `127.0.0.1:18080`，可在服务端配置文件中修改。
- `/webmonitor` 提供服务启停与玩家 API key 的查看、生成、删除。
- 物理专用服务器始终要求 API key；只有物理客户端进程中的集成服务器/单人游戏可免 key。Key 存储在 `config/web_monitor_interface-keys.json`。
- REST 端点提供服务器、世界、方块、实体、批量方块、三种 Forge capability 快照和事件目录查询。
- Block entity、物品和流体默认返回结构化 JSON NBT；Block entity 另有 SNBT 端点。Capability 仅返回能量、物品、流体的只读快照。不存在直接方法调用、方法黑白名单或登录/登出 API。
- WebSocket 支持 API key 认证、订阅/退订、心跳和 `custom:` 自定义事件；当前事件监听器实际发布聊天、玩家加入和离开事件。

## 已确认的限制

- REST 查询会切换到 Minecraft 服务器主线程，Netty I/O 线程只处理 HTTP、认证和 JSON 传输；单次等待上限为 10 秒。
- REST 与 WebSocket 统一使用 API key：`Authorization: Bearer`、`X-Api-Key`、`X-Auth-Token`、`key` 或 `token`。WebSocket 在 HTTP Upgrade 阶段校验，不提供 JSON `auth` 动作。
- 事件目录仅包含已实际发布的 `chat.public`、`player.join` 和 `player.quit`。
- 物理客户端的集成服务器无需 key，也不实施 key 限流，这是本地使用场景的有意设计。WebSocket 的 `max_rate` 与 `tps_update_interval` 配置尚未使用。
- `/webmonitorinterface` 已移除；只保留 `/webmonitor`。
- NBT 默认转为 JSON 标量、数组、对象与列表。NBT 数值类型标记和 byte/int/long 数组的原始 NBT 类型不会单独保留；需要文本表示时使用 BE SNBT 端点。

## 验证结果

- 已检查 Git 历史：仓库已经存在，当前检查前已有 `Initial commit` 与后续重构提交。
- 已检查 mod 元数据、命令、认证、REST 路由和 WebSocket 实现；未发现已经删除的 Auth API、MethodGuard 或 BE/Capability 方法调用代码重新出现。
- 已定位 JDK 17.0.19 与 Gradle 8.5，并尝试执行 `gradle build --no-daemon`。构建在沙箱启动阶段失败：Gradle 无法加载 Linux amd64 的 `libnative-platform.so`，尚未进入项目配置或 Java 编译阶段。因此本次未能完成编译或运行时验证。
