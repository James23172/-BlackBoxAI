# 多人协作与部署 — 实施设计文档

## 概述

基于 `多人协作与部署方案.md` 架构文档，在现有单机系统上补齐缺失功能，使其支持 5 台电脑局域网分布式部署。策略：先在单机 (`localhost`) 跑通全部功能，再通过启动参数切换到多机部署。

实施拆分为 6 条独立功能线，每条从前端到后端一次性贯通。

---

## 第一部分：安全层

### 1.1 前端 SHA-256 传输

**文件：** `display/src/main/resources/web/index.html`

新增纯 JS SHA-256 函数（约80行，无外部依赖）。登录和注册时前端先 hash 密码再提交。

```javascript
// 新增
function sha256(str) { /* 纯JS SHA-256实现 */ }

// doLogin() 改：
body: JSON.stringify({username: u, transHash: sha256(p)})
//                            ^^^^^^^^^ 从 password 改为 transHash

// doRegister() 同改：
body: JSON.stringify({username: u, transHash: sha256(p), role: role})
```

### 1.2 AuthServer 适配两层哈希

**文件：** `auth/src/main/java/inspection/auth/AuthServerMain.java`

**启动时清旧格式：** 在 retry 循环之前加一行清理，防止旧 `salt:SHA-256(salt+明文)` 格式残留。

```java
// AuthServerMain.main() — 紧接 pool 初始化之后，retry 循环之前
try (Jedis jedis = pool.getResource()) {
    jedis.del("auth:user:admin");
} catch (Exception ignored) {}

// 再走现有的 5 次 retry 注册 admin
for (int i = 0; i < 5; i++) { ... }
```

**接口字段名变更：** `body.getString("password")` → `body.getString("transHash")`（LoginHandler 和 RegisterHandler）。

`PasswordHasher.hash()` 和 `verify()` 接口不变，语义变为接收前端 SHA-256 结果。

### 1.3 WebSocket Token 认证

**文件：** `display/src/main/java/inspection/display/CommandReceiver.java`、`index.html`

**CommandReceiver 新增：**

```java
// 连接状态
private final Map<WebSocket, ConnState> connStates = new ConcurrentHashMap<>();

// ConnState 内部类
static class ConnState {
    String username;
    String role;
    String machineId;  // 从 Display 的 --machine 参数获取（主/B/C/D/E）
}

// onOpen: 注册连接（初始未认证）
onOpen(WebSocket conn, ClientHandshake handshake):
    connStates.put(conn, new ConnState());  // username=null

// onClose: 清理
onClose(WebSocket conn, int code, String reason, boolean remote):
    connStates.remove(conn);

// onMessage 第一条消息必须是 AUTH
onMessage(WebSocket conn, String message):
    ConnState state = connStates.get(conn);
    if (state == null || state.username == null) {
        if (!"AUTH".equals(msg.type)) {
            conn.close(4001, "请先认证");
            return;
        }
        handleAuth(conn, msg);  // 校验 token → 写入 connStates (含 machineId)
        return;
    }
    // 后续消息正常处理
```

**Token 验证方式：** CommandReceiver 收到 AUTH 消息后，向 AuthServer 的 `/api/auth/verify?token=xxx` 发 HTTP GET 请求。AuthServer 可能在其他机器上，因此 Display 需要知道 AuthServer 地址（见 1.6）。

**前端：** `ws.onopen` 时立即发送 `{type: "AUTH", token: getToken()}`。服务端 AUTH 成功后回复 `{type: "AUTH_OK", machine: "B"}`，前端存入 `MACHINE_ID`。此后所有 `ADD_CAR` / `PAUSE scope=personal` 的命令自动带 `machine` 字段。

### 1.4 后端权限校验

**文件：** `CommandReceiver.java`

每条命令处理前，根据 role 查 `PermissionManager.DEFAULT_PERMISSIONS`：

| 命令 | 允许角色 |
|------|---------|
| SET_CONFIG / RESET / TOGGLE_OBSTACLE / 用户注册 | configurator only |
| START / PAUSE / ADD_CAR / REMOVE_CAR | configurator + operator |
| 查看 / 回放 | 所有角色 |

越权返回 `{type: "ERROR", message: "权限不足"}`。

### 1.5 配置员 IP 限制

**前端：** `showMain()` 中 `role === 'configurator' && hostname !== 'localhost'` → 弹窗登出。

**后端：** `LoginHandler` 中校验远程 IP：

```java
String ip = exchange.getRemoteAddress().getAddress().getHostAddress();
boolean isLocal = "127.0.0.1".equals(ip) 
               || "0:0:0:0:0:0:0:1".equals(ip) 
               || "::1".equals(ip);
if (role == CONFIGURATOR && !isLocal) reject();
```

### 1.6 AuthServer 地址发现（多机部署关键）

**问题：** 前端硬编码 `fetch('http://localhost:8890/api/auth/login')`。多机时 AuthServer 只在主公机上，客户端 B/C/D/E 的浏览器用 `localhost:8890` 访问不到。

**方案：** Display 在启动时接受 `--auth-host` 和 `--auth-port` 参数，通过一个 HTTP 端点暴露给前端。

**Display 新增 HTTP 端点：**

```java
// DisplayMain 新增 context
server.createContext("/api/config", new ConfigHandler());

// ConfigHandler 返回
{
  "authServer": "http://192.168.1.100:8890",
  "machine": "B"
}
```

**Display 参数新增：** `--auth-host`（默认 `localhost`）、`--auth-port`（默认 `8890`）。

**前端改造：** 页面加载时先 `fetch('/api/config')` 获取 `authServer` URL，后续所有登录/注册/验证请求都用这个地址。

```javascript
var authServer = 'http://localhost:8890';  // 默认值

async function loadConfig() {
    try {
        var r = await fetch('/api/config');
        var cfg = await r.json();
        authServer = cfg.authServer;
        window.__machine = cfg.machine;  // Display 所在机器标识
    } catch(e) { /* 保持默认值 localhost */ }
}

// 然后在 doLogin() 中使用
var r = await fetch(authServer + '/api/auth/login', ...);
```

**主公机启动（不加 --auth-host，默认 localhost 即可）：**
```
java inspection.display.DisplayMain --http-port 8888 --machine 主
```

**客户端 B 启动（指定 AuthServer 在主公机）：**
```
java inspection.display.DisplayMain --redis-host 192.168.1.100 --mq-host 192.168.1.100 --auth-host 192.168.1.100 --http-port 8889 --machine B
```

---

## 第二部分：角色面板

**文件：** `display/src/main/resources/web/index.html` — `buildPanel()`

### 2.1 配置员面板

- 全局控制按钮文案改为"全局开始""全局暂停""全局重置"
- 全局暂停时显示红色横幅 "全局已暂停 — 所有运行员无法操作"
- 用户管理：创建用户表单 + 用户列表（用户名、角色、删除按钮）
- 障碍物编辑：保留右键切换 + 增加"随机生成"按钮

### 2.2 运行员面板

- "我的小车"表格：筛选 `carOwner === MACHINE_ID` 的 Car（`MACHINE_ID` 从 `/api/config` 获取，值为主/B/C/D/E）
- "暂停我的车""恢复我的车"按钮，命令带 `scope: "personal"`（由 `MACHINE_ID` 标识操作对象）
- 配置员全局暂停时显示红色警告 + 所有按钮变灰
- 权限说明灰色小字

### 2.3 分析员面板

完整回放控件（详见第五部分）。

---

## 第三部分：运行员归属与分权调度

### 3.1 新增 Redis Key

```
pause:global                  String  "true" / 不存在
pause:operator:{machineId}    String  "true" / 不存在   (machineId = 主/B/C/D/E)
car:{carId}:owner             String  machineId (主/B/C/D/E)
```

### 3.2 Controller 调度逻辑

**文件：** `controller/src/main/java/inspection/controller/ControllerAgent.java`

```
每 tick:
  1. 检查 pause:global 是否存在 → 存在则全部跳过
  2. 遍历所有车:
     ├─ 读 car:{id}:owner → 得到 machineId（如 "B"）
     ├─ 读 pause:operator:{machineId}
     ├─ 该 machineId 暂停 → 跳过这辆车
     └─ 未暂停 → 正常发 MOVE_STEP
  3. 广播 REFRESH_ALL
```

### 3.3 命令分发

**文件：** `CommandReceiver.java`

前端消息新增 `scope` 字段：

```json
{type: "PAUSE", scope: "global"}     // 仅 configurator
{type: "PAUSE", scope: "personal"}   // operator，后端从 connStates 取 machineId
{type: "START", scope: "global"}     // 仅 configurator
{type: "START", scope: "personal"}   // operator，同上取 machineId，前提 pause:global 不存在
```

### 3.4 Car 创建写入 owner

**文件：** `task-configurator` `TaskConfiguratorMain.java`

- 初始化（RESET）：Car owner 留空（属于"系统"，配置员全局控制）
- 增量添加（ADD_CAR）：`blackboard.setCarOwner(carId, data.getString("machine"))` — 该值由前端从 `MACHINE_ID` （AUTH_OK 返回）自动填入

### 3.5 BlackboardClient 新增方法

```java
void setCarOwner(String carId, String machineId);
String getCarOwner(String carId);
void setGlobalPause(boolean paused);
boolean isGlobalPaused();
void setOperatorPause(String machineId, boolean paused);
boolean isOperatorPaused(String machineId);
```

---

## 第四部分：多机部署参数化

### 4.1 新增 ArgsParser

**文件：** `common/src/main/java/inspection/common/config/ArgsParser.java`（新建）

```java
public class ArgsParser {
    private final Map<String, String> map = new HashMap<>();
    public ArgsParser(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--") && i + 1 < args.length) {
                map.put(args[i], args[i + 1]); i++;
            }
        }
    }
    public String get(String key, String def) { return map.getOrDefault(key, def); }
    public int getInt(String key, int def) {
        String v = map.get(key); return v != null ? Integer.parseInt(v) : def;
    }
}
```

### 4.2 各模块参数清单

统一使用 `--mq-host` / `--mq-port`（CarMain 已接收 `--rabbit-host` 的历史参数名同步改为 `--mq-host`）。

| 模块 | 参数 | 说明 |
|------|------|------|
| **AuthServerMain** | `--redis-host`, `--redis-port` | 不碰 MQ |
| **DisplayMain** | `--redis-host`, `--redis-port`, `--mq-host`, `--mq-port`, `--auth-host`, `--auth-port`, `--http-port`, `--machine` | `--machine` 值: `主`/`B`/`C`/`D`/`E` |
| **ControllerMain** | `--redis-host`, `--redis-port`, `--mq-host`, `--mq-port`, `--instance-id`, `--total-instances` | |
| **NavigatorMain** | `--redis-host`, `--redis-port`, `--mq-host`, `--mq-port` | |
| **TaskConfiguratorMain** | `--redis-host`, `--redis-port`, `--mq-host`, `--mq-port` | |
| **TargetPlannerMain** | `--redis-host`, `--redis-port`, `--mq-host`, `--mq-port` | |
| **ReplayMain** | `--redis-host`, `--redis-port` | 端口固定 8893 |
| **CarMain** | `--redis-host`, `--redis-port`, `--mq-host`, `--mq-port`, `--car-id` | `--rabbit-host` → `--mq-host`（统一参数名） |

### 4.3 Display `--machine` 传递到前端

AUTH 成功后服务端回复：

```json
{type: "AUTH_OK", machine: "B"}
```

前端存入全局变量，后续 ADD_CAR 等消息携带。

### 4.4 各机器启动命令

**主公机 (IP: 192.168.1.100)：**
```
java inspection.auth.AuthServerMain
java inspection.replay.ReplayMain
java inspection.controller.ControllerMain
java inspection.display.DisplayMain --http-port 8888 --machine 主
java inspection.navigator.NavigatorMain
java inspection.car.CarMain Car001 --car-id Car001
java inspection.car.CarMain Car002 --car-id Car002
```

**客户端 B (IP: 192.168.1.101)：**
```
java inspection.taskconfigurator.TaskConfiguratorMain --redis-host 192.168.1.100 --mq-host 192.168.1.100
java inspection.display.DisplayMain --redis-host 192.168.1.100 --mq-host 192.168.1.100 --http-port 8889 --machine B
java inspection.navigator.NavigatorMain --redis-host 192.168.1.100 --mq-host 192.168.1.100
java inspection.car.CarMain --car-id Car003 --redis-host 192.168.1.100 --mq-host 192.168.1.100
java inspection.car.CarMain --car-id Car004 --redis-host 192.168.1.100 --mq-host 192.168.1.100
```

客户端 C/D/E 同理，按架构文档模块分配表启动各自模块。

---

## 第五部分：分析员图表与回放

### 5.1 端口

ReplayServer 使用 **8893**（不与 Display 的 8888~8892 冲突）。

### 5.2 批量快照接口

**文件：** `replay/src/main/java/inspection/replay/ReplayMain.java`

新增 `BatchHandler`：

```
GET /api/replay/snapshots?from=0&to=1999
→ 返回 JSON 数组（一次性，避免 2000 次串行 HTTP 卡死）
```

### 5.3 前端回放控件

**文件：** `index.html` — `buildPanel('analyst')`

```
⏮ 后退10帧  ⏪ 后退1帧  ▶ 播放/⏸ 暂停  ⏩ 前进1帧  ⏭ 前进10帧
速度: [0.5x] [1x] [2x] [4x]
━━━━━━━●━━━━━━ 进度条（可拖动跳转）
Tick: 1280 / 2048
```

### 5.4 探索率折线图

新建 `<canvas id="chartCanvas" width="320" height="200">`。

数据来源：一次性加载的所有快照 `replaySnapshots`。

```javascript
function drawExploredChart() {
    // Canvas绑折线图：X轴时间帧，Y轴探索率0%~100%
    // 坐标系 + 刻度 + 折线 + 标签
}
```

### 5.5 统计面板

- 总步数、最终探索率
- 单车统计表：`Car001: 342步, 覆盖率20.1%`

### 5.6 实时/回放模式切换

分析员页面需要两种渲染来源：

- **实时模式：** `render(state)` 正常消费 WebSocket 推送的 `currentState`
- **回放模式：** `render(replaySnapshots[replayIdx])` 渲染历史快照

顶部按钮切换模式，互斥：进入回放模式时暂停实时 render，退出回放时恢复。

```javascript
var replayMode = false;  // false=实时, true=回放

ws.onmessage = function(event) {
    var msg = JSON.parse(event.data);
    if (!replayMode) {
        currentState = msg;
        render(msg);
    }
    // 同时始终更新统计数字
    updateLiveStats(msg);
};

function replaySeek(idx) {
    replayMode = true;
    replayIdx = idx;
    render(replaySnapshots[replayIdx]);
}

function exitReplayMode() {
    replayMode = false;
    replayPlaying = false;
    render(currentState);
}
```

### 5.7 ReplayServer 参数化

**文件：** `replay/src/main/java/inspection/replay/ReplayMain.java`

端口改为从参数解析（默认 8893），支持 `--redis-host`、`--redis-port`、`--port`。

---

## 第六部分：剩余项

### 6.1 PasswordHasher 语义更新

**文件：** `auth/src/main/java/inspection/auth/PasswordHasher.java`

接口不变，更新注释说明传入的已是前端 SHA-256 结果。

### 6.2 Launcher 微调

**文件：** `common/src/main/java/inspection/common/Launcher.java`

- Display 启动加 `--machine 主` 参数
- 其他模块不加远程参数（单机调试用 localhost）

### 6.3 架构文档更新

**文件：** `多人协作与部署方案.md`

末尾追加实施记录和每台机器的精确启动命令。

---

## 改动文件清单

| 文件 | 改动类型 | 涉及部分 |
|------|---------|---------|
| `display/.../web/index.html` | 大量修改 | 一、二、三、五 |
| `auth/.../AuthServerMain.java` | 中等修改 | 一 |
| `auth/.../PasswordHasher.java` | 注释更新 | 六 |
| `display/.../CommandReceiver.java` | 大量修改 | 一、三 |
| `common/.../BlackboardClient.java` | 新增方法 | 三 |
| `common/.../config/ArgsParser.java` | **新建** | 四 |
| `controller/.../ControllerAgent.java` | 修改调度逻辑 | 三 |
| `controller/.../ControllerMain.java` | 参数解析 | 四 |
| `display/.../DisplayMain.java` | 参数解析 + machine + ConfigHandler(`/api/config`) | 一、四 |
| `navigator/.../NavigatorMain.java` | 参数解析 | 四 |
| `task-configurator/.../TaskConfiguratorMain.java` | 参数解析 + owner | 三、四 |
| `target-planner/.../TargetPlannerMain.java` | 参数解析 | 四 |
| `car/.../CarMain.java` | `--rabbit-host`→`--mq-host` | 四 |
| `replay/.../ReplayMain.java` | 参数解析 + 批量接口 + 端口8893 | 四、五 |
| `common/.../Launcher.java` | `--machine 主` | 六 |
| `多人协作与部署方案.md` | 追加实施记录 | 六 |

## 不涉及的文件

以下模块无需修改：`common/model/`、`common/enums/`、`common/client/MessageBusClient.java`、`common/client/DistributedLock.java`、`navigator/BFSPlanner.java`、`navigator/AStarPlanner.java`、`navigator/PathPlanner.java`、`car/CarAgent.java`、`car/Illuminator.java`、`car/DynamicObstacleManager.java`、`auth/model/Role.java`、`auth/model/User.java`、`auth/UserManager.java`、`auth/PermissionManager.java`
