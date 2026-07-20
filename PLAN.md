# WebInterface Mod - 设计文档

> 生成时间: 2026-07-13
> 生成工具: OpenCode Plan + DeepSeek V4 Pro / GLM-5.2 (NVIDIA NIM)

---

## 一、架构设计

```
┌──────────────────────────────────────────────────────────────┐
│                     Minecraft Server                          │
│  ┌──────────────────────────────────────────────────────────┐│
│  │                   Forge Mod Layer                        ││
│  │  ┌──────────────┐  ┌──────────────┐  ┌───────────────┐  ││
│  │  │  Event       │  │  Command     │  │  Config       │  ││
│  │  │  Listener    │──▶  Handler     │──▶  Manager      │  ││
│  │  └──────┬───────┘  └──────┬───────┘  └───────┬───────┘  ││
│  │         │                 │                   │          ││
│  │         ▼                 ▼                   ▼          ││
│  │  ┌──────────────────────────────────────────────────┐   ││
│  │  │              Web Service Layer                    │   ││
│  │  │  ┌─────────────────┐  ┌─────────────────────┐    │   ││
│  │  │  │  REST Handler   │  │  WS Event Publisher  │    │   ││
│  │  │  └────────┬────────┘  └──────────┬──────────┘    │   ││
│  │  │           │                      │                │   ││
│  │  │           ▼                      ▼                │   ││
│  │  │  ┌─────────────────────────────────────────────┐  │   ││
│  │  │  │     Netty Embedded Server (Forge自带)       │  │   ││
│  │  │  │     - HTTP on /api/*                        │  │   ││
│  │  │  │     - WebSocket on /ws                      │  │   ││
│  │  │  └─────────────────────────────────────────────┘  │   ││
│  │  └──────────────────────────────────────────────────┘   ││
│  └──────────────────────────────────────────────────────────┘│
│                                                              │
│                  ┌──────────────┐                             │
│                  │  Config File │                             │
│                  │ (TOML)       │                             │
│                  └──────────────┘                             │
└──────────────────────────────────────────────────────────────┘
                     │ REST / WebSocket
                     ▼
            ┌───────────────┐
            │  外部客户端    │
            └───────────────┘
```

## 二、模块划分

```
src/main/java/com/example/webinterface/
├── WebInterfaceMod.java          // @Mod 主类
├── config/
│   └── ModConfig.java            // TOML 配置加载 + 权限规则
├── event/
│   └── MinecraftEventListener.java
├── command/
│   └── WebInterfaceCommand.java  // /webinterface
├── web/
│   ├── WebServer.java            // Netty 服务
│   ├── handler/
│   │   ├── RestHandler.java      // REST 路由
│   │   └── WebSocketHandler.java // WS 管理
│   ├── api/
│   │   ├── BlockApi.java         // 方块 BS/BE/CAP
│   │   ├── EntityApi.java        // 实体/玩家
│   │   ├── BatchApi.java         // 批量查询
│   │   ├── AuthApi.java          // 登录/权限
│   │   └── CapabilityApi.java    // Capability 访问
│   └── event/
│       ├── EventPublisher.java   // 事件推送管理器
│       └── events/               // 事件类型定义
├── security/
│   └── MethodGuard.java          // 方法权限控制
└── model/
    └── ServerState.java          // 线程安全快照
```

---

## 三、REST API 总览

### 基础端点

| Method | Path | 说明 |
|--------|------|------|
| GET | `/api/v1/status` | 服务器状态 |
| GET | `/api/v1/tps` | TPS |
| GET | `/api/v1/worlds` | 维度列表 |

### 方块数据

| Method | Path | 说明 |
|--------|------|------|
| GET | `/api/v1/world/{dim}/block?x&y&z` | 方块完整数据（BS+BE+CAP） |
| GET | `/api/v1/world/{dim}/block/property?x&y&z&key=` | 单 BS 属性 |
| GET | `/api/v1/world/{dim}/block/blockentity?x&y&z&path=` | BE NBT |
| POST | `/api/v1/world/{dim}/block/blockentity/invoke?x&y&z` | 调用 BE 方法 |
| GET | `/api/v1/world/{dim}/block/capability?x&y&z&cap=` | 获取 Capability |
| POST | `/api/v1/blocks/batch` | 批量查询+过滤 |
| POST | `/api/v1/blocks/batch/count` | 批量计数 |

### 实体/玩家

| Method | Path | 说明 |
|--------|------|------|
| GET | `/api/v1/world/{dim}/entity/{eid}` | 实体详情 |
| GET | `/api/v1/world/{dim}/player/{eid}` | 玩家+背包 |
| GET | `/api/v1/world/{dim}/entities/aabb?minX&minY&minZ&maxX&maxY&maxZ` | AABB 实体列表 |

### Capability

| Method | Path | 说明 |
|--------|------|------|
| GET | `/api/v1/capabilities?target=&dim=&x=&y=&z=` | 列出对象所有 CAP |
| GET | `/api/v1/capabilities/{target}/{cap}?ref=...` | CAP 详情/快照 |
| POST | `/api/v1/capabilities/{target}/{cap}/execute?ref=...` | 调用 CAP 方法 |

### 权限/认证

| Method | Path | 说明 |
|--------|------|------|
| POST | `/api/v1/auth/login` | 登录 |
| POST | `/api/v1/auth/logout` | 注销 |
| GET | `/api/v1/auth/me` | 当前会话 |
| GET | `/api/v1/permissions/methods` | 方法权限列表 |
| GET | `/api/v1/permissions/config` | 权限配置（OP） |
| PUT | `/api/v1/permissions/config` | 更新权限配置（OP） |
| GET | `/api/v1/events/catalog` | 可订阅事件目录 |

---

## 四、WebSocket 事件

### 端点
```
WS /ws?token=<session>&filter=<预设订阅>&max_rate=<数量>
```

### 客户端→服务端

| action | 说明 |
|--------|------|
| `subscribe` | 订阅事件（支持 `*` 通配符） |
| `unsubscribe` | 取消订阅 |
| `publish` | 发布自定义事件（`custom:` 前缀） |
| `ping` | 心跳 |

### 服务端→客户端

| type | 说明 |
|------|------|
| `ack` | 操作确认 |
| `event` | 事件推送（含 `seq` 单调递增序号） |
| `pong` | 心跳响应 |
| `close` | 服务端关闭通知 |

### 事件列表

| 事件 | 说明 |
|------|------|
| `world.block.update` | 方块变更 |
| `world.entity.spawn` | 实体生成 |
| `world.entity.despawn` | 实体消失 |
| `world.entity.move` | 实体移动（高频节流） |
| `world.player.death` | 玩家死亡 |
| `world.player.respawn` | 玩家重生 |
| `chat.public` | 公共聊天 |
| `player.join` | 玩家加入 |
| `player.quit` | 玩家退出 |
| `server.starting/started/stopping` | 服务端状态 |
| `server.tps` | TPS 更新 |
| `capability.changed` | Capability 变化（带阈值过滤） |
| `batch.progress` | 批量查询进度 |

---

## 五、通用约定

### 统一响应格式
```json
{
  "code": 0,
  "msg": "ok",
  "data": { ... },
  "trace_id": "uuid-v4",
  "server_tick": 12345
}
```

### 错误码

| code | HTTP | 含义 |
|------|------|------|
| 0 | 200 | 成功 |
| 1001 | 400 | 参数错误 |
| 1002 | 404 | 资源不存在/未加载 |
| 2001 | 401 | 未认证 |
| 2002 | 403 | 方法被禁止 |
| 2003 | 403 | 权限不足 |
| 3001 | 400 | 方法不存在 |
| 3002 | 500 | 调用异常 |
| 4001 | 429 | 限流 |

---

## 六、权限控制

### 权限等级

| level | 名称 | 说明 |
|-------|------|------|
| 4 | op | 全部方法可用 |
| 3 | operator | 白名单 + role_overrides |
| 2 | normal | 默认 `get[A-Z]*`、`is*`、`has*` |
| 1 | restricted | 显式 allowed 列表 |
| 0 | guest | 仅公开只读 API |

### 判断流程
```
1. 无 session → level=0
2. level==4 → allow
3. method_overrides 有配置且 level<min_level → deny
4. level==3 → role_overrides 豁免检查
5. 匹配 blacklist → deny
6. 匹配 whitelist → allow
7. 否则 deny
```

### 配置示例
```toml
[permissions]
default_level = 2
blacklist = ["set.*", "executeCommand", "shutdown.*"]
whitelist_default = ["get[A-Z].*", "is[A-Z].*", "has[A-Z].*"]

[permissions.role_overrides.operator]
allow = ["shutdown"]

[permissions.method_overrides]
"BlockEntity.executeCommand" = { min_level = 4 }
"Capability.extract_energy" = { min_level = 3 }
```

---

## 七、实现要点

1. **线程安全**：所有 MC 调用排入主线程，Netty IO 线程仅做 JSON 解析
2. **区块未加载**：`chunk_loaded: false` 时返回已有信息，不强制加载
3. **Player 对象**：通过 UUID 查找在线玩家，不可获取时检查 `ignore_player` 参数
4. **快照缓存**：高频只读查询缓存 1 tick，缓解 TPS 压力
5. **热重载**：`/webinterface reload` + PUT 配置触发 `AtomicReference` 热替换
6. **Cap 方法映射**：内置 `IEnergyStorage`/`IItemHandler`/`IFluidHandler` 适配表
7. **限流**：每会话令牌桶，默认 120 req/min
8. **测试粒度**：BlockApi、EntityApi、CapabilityApi、MethodGuard、EventPublisher 五类单元测试