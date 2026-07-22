# Web Monitor Interface Web API

实现版本：1.0.0。本文档描述当前代码实际提供的接口；服务默认监听 `127.0.0.1:18080`。

## 认证

只有物理客户端进程中的集成服务器/单人游戏不校验 key。物理专用服务器始终要求有效 key，不能通过配置关闭。

玩家在游戏内使用以下命令管理自己的 key：

```text
/webmonitor key
/webmonitor key generate [comment]
/webmonitor key delete <key>
```

REST 请求按下列优先顺序接受 key：`Authorization: Bearer <key>`、`X-Api-Key`、`X-Auth-Token`、查询参数 `key` 或 `token`。请优先使用 HTTP 请求头，避免 key 出现在访问日志和 URL 历史中。

专用服务器的 REST 限流默认为每个有效 key 每分钟 120 次，可由 `rest.rate_limit_per_minute` 调整。不存在 login、logout 或 session API。

## 响应与错误

所有 REST 响应使用以下封装：

```json
{
  "code": 0,
  "msg": "ok",
  "data": {},
  "trace_id": "uuid",
  "server_tick": 123
}
```

`code` 为 `0` 时成功。常用错误：`1001` 参数错误（HTTP 400）、`1002` 资源未找到或区块未加载（404）、`2001` 未提供有效 API key（401）、`2002` 禁止的写入/调用操作（403）、`4001` 限流（429）、`3002` 服务端异常（500）。

## REST 端点

| 方法 | 路径 | 参数/请求体 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/v1/status` | | 服务、玩家、Web 服务和认证状态 |
| GET | `/api/v1/tps` | | TPS、平均 tick 时间、tick 计数 |
| GET | `/api/v1/worlds` | | 已加载维度、区块数和难度 |
| GET | `/api/v1/world/{dim}/blockstate` | `x`、`y`、`z`、`key` | 单个方块状态属性 |
| GET | `/api/v1/world/{dim}/blockentity` | `x`、`y`、`z`、可选 `path` | 完整 BE 结构化 NBT，或点分 NBT 路径的结构化值 |
| GET | `/api/v1/world/{dim}/blockentity/snbt` | `x`、`y`、`z`、可选 `path` | 完整 BE SNBT，或点分 NBT 路径的 SNBT 字符串 |
| GET | `/api/v1/world/{dim}/blockentity/capability` | `x`、`y`、`z`、`cap` | `energy`、`items` 或 `fluid` 的只读快照 |
| GET | `/api/v1/world/{dim}/entity/{entityId}` | | 按运行时实体 ID 查询实体 |
| GET | `/api/v1/world/{dim}/entity/{entityId}/capability` | `cap` | 实体的 capability 只读快照 |
| GET | `/api/v1/world/{dim}/player/{uuidOrName}` | | 在线玩家、背包和状态 |
| GET | `/api/v1/world/{dim}/players` | | 当前维度所有玩家，返回 `displayName` 和 `uuid` |
| GET | `/api/v1/world/{dim}/entities/aabb` | `minX`、`minY`、`minZ`、`maxX`、`maxY`、`maxZ`；可选 `type`、`limit` | AABB 内实体；`limit` 最大 1000 |
| POST | `/api/v1/world/{dim}/blocks` | JSON 请求体；可选 `?type=` | 批量方块查询，`dim` 从 URL 获取 |
| POST | `/api/v1/world/{dim}/blockstates` | JSON 请求体 | 批量 blockstate 查询（仅 positions 数组） |
| POST | `/api/v1/world/{dim}/blockentities` | JSON 请求体 | 批量 block entity NBT 查询（仅 positions 数组） |
| POST | `/api/v1/world/{dim}/blockentities/snbt` | JSON 请求体 | 批量 block entity SNBT 查询（仅 positions 数组） |
| POST | `/api/v1/world/{dim}/blockentity/capabilities` | JSON 请求体 | 批量 block entity capability 可用性列表查询（仅 positions 数组） |
| GET | `/api/v1/events/catalog` | | 可订阅事件名称与描述 |

`{dim}` 可使用 `minecraft:overworld`、`minecraft:the_nether`、`minecraft:the_end`，或有效的维度资源位置。所有 capability 端点均为只读快照，不会执行游戏对象方法。

## 批量查询

### POST /api/v1/world/{dim}/blocks

替代原 `/api/v1/blocks/batch`，dimension 从 URL 路径获取，不再出现在请求体中。支持 `positions` 数组和 `region` 区域两种方式，可选 `?type=` 查询参数过滤方块类型。

```json
{
  "positions": [{"x": 0, "y": 64, "z": 0}],
  "filter": "minecraft:chest",
  "limit": 4096
}
```

也可使用 `region` 替代 `positions`：`{ "minX": 0, "minY": 64, "minZ": 0, "maxX": 15, "maxY": 64, "maxZ": 15 }`。区域会最多展开 4096 个位置；最多返回 1000 个方块。

### POST /api/v1/world/{dim}/blockstates

仅接受 `positions` 数组，返回每个位置的 block state 信息：

```json
{
  "positions": [{"x": 0, "y": 64, "z": 0}]
}
```

### POST /api/v1/world/{dim}/blockentities

仅接受 `positions` 数组，返回每个位置的 block entity 结构化 NBT：

```json
{
  "positions": [{"x": 0, "y": 64, "z": 0}]
}
```

### POST /api/v1/world/{dim}/blockentities/snbt

同上，返回 SNBT 字符串。

### POST /api/v1/world/{dim}/blockentity/capabilities

仅接受 `positions` 数组，返回每个位置的 capability 可用性列表：

```json
{
  "positions": [{"x": 0, "y": 64, "z": 0}]
}
```

## WebSocket

连接端点：`ws://127.0.0.1:18080/ws`。物理专用服务器在 HTTP Upgrade 请求时使用与 REST 相同的 API key 输入方式：`Authorization: Bearer <key>`、`X-Api-Key`、`X-Auth-Token`、`key` 或 `token` 查询参数。未提供有效 key 时，握手返回 HTTP 401；不存在 WebSocket JSON `auth` 动作。连接成功后可发送：

```json
{"action":"subscribe","events":["chat.public","player.*"]}
{"action":"unsubscribe","event":"chat.public"}
{"action":"ping"}
{"action":"publish","event":"custom:client.notice","data":{"message":"hello"}}
```

订阅筛选支持 `*` 通配符。服务端事件格式为：

```json
{
  "type": "event",
  "event": "chat.public",
  "ts": 0,
  "server_tick": 0,
  "data": {}
}
```

`events/catalog` 仅列出并由 Minecraft 事件监听器实际发布的 `chat.public`、`player.join` 和 `player.quit`。