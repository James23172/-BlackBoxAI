# 多人协作与部署 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有单机系统上补齐安全认证、角色分权、多机部署参数化、分析员回放图表等全部缺失功能，使系统支持5台电脑局域网分布式部署。

**Architecture:** 沿袭现有黑板+消息驱动架构，在 security/ownership/params/frontend-panels 四条线上增量修改。不引入新模块，不改动核心算法模块（BFSPlanner、CarAgent、Illuminator 等）。

**Tech Stack:** Java 17, Maven 多模块, Redis 7 (Jedis 5.2), RabbitMQ 3.x, Fastjson2, 内嵌 HTTP/WebSocket (org.java_websocket), 纯 JS 无框架前端

## Global Constraints

- 所有模块通过 `--redis-host`/`--redis-port`/`--mq-host`/`--mq-port` 参数覆盖连接地址
- 统一使用 `--mq-host`/`--mq-port`（CarMain 旧参数 `--rabbit-host`/`--rabbit-port` 同步改名）
- machine 标识统一为 `主`/`B`/`C`/`D`/`E`
- 前端单 HTML 文件，通过 role 动态切换面板
- 密码前端 SHA-256 后传输，后端 salt+SHA-256 存储
- 配置员仅限本机 localhost 登录

---

### Task 1: 新建 ArgsParser 工具类

**Files:**
- Create: `common/src/main/java/inspection/common/config/ArgsParser.java`

**Interfaces:**
- Produces: `ArgsParser(String[] args)` — constructor; `String get(String key, String defaultValue)`; `int getInt(String key, int defaultValue)`

- [ ] **Step 1: 创建 ArgsParser.java**

```java
package inspection.common.config;

import java.util.HashMap;
import java.util.Map;

/**
 * 命令行参数解析工具
 * 用法: ArgsParser args = new ArgsParser(args);
 *       String host = args.get("--redis-host", ConfigConstants.REDIS_HOST);
 */
public class ArgsParser {
    private final Map<String, String> map = new HashMap<>();

    public ArgsParser(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--") && i + 1 < args.length) {
                map.put(args[i], args[i + 1]);
                i++;
            }
        }
    }

    public String get(String key, String defaultValue) {
        return map.getOrDefault(key, defaultValue);
    }

    public int getInt(String key, int defaultValue) {
        String v = map.get(key);
        return v != null ? Integer.parseInt(v) : defaultValue;
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `cd C:\workplace\ruanti\BlackBoxAI && mvn compile -pl common -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add common/src/main/java/inspection/common/config/ArgsParser.java
git commit -m "feat: 新增 ArgsParser 命令行参数解析工具"
```

---

### Task 2: 前端 SHA-256 + authServer 发现

**Files:**
- Modify: `display/src/main/resources/web/index.html`

**Interfaces:**
- Produces: `sha256(str)` → hex string; `loadConfig()` — fetches `/api/config`; `var authServer` — global auth URL

- [ ] **Step 1: 在 `<script>` 开头添加 SHA-256 纯 JS 实现**

在 `var ws = null, ...` 行之前插入：

```javascript
// SHA-256 纯 JS 实现（无外部依赖）
function sha256(str) {
    var r = function(n,b){return(n>>>b)|(n<<(32-b))};
    var C = function(q,n){var b=q>>>16,a=n>>>16;q&=0xffff;n&=0xffff;return ((b*a)<<16)+(q*n);};
    var S = function(n,b){return(n>>>b)|(n<<(32-b))};
    var K = [1116352408,1899447441,3049323471,3921009573,961987163,1508970993,2453635748,2870763221,
             3624381080,310598401,607225278,1426881987,1925078388,2162078206,2614888103,3248222580,
             3835390401,4022224774,264347078,604807628,770255983,1249150122,1555081692,1996064986,
             2554220882,2821834349,2952996808,3210313671,3336571891,3584528711,113926993,338241895,
             666307205,773529912,1294757372,1396182291,1695183700,1986661051,2177026350,2456956037,
             2730485921,2820302411,3259730800,3345764771,3516065817,3600352804,4094571909,275423344,
             430227734,506948616,659060556,883997877,958139571,1322822218,1537002063,1747873779,
             1955562222,2024104815,2227730452,2361852424,2428436474,2756734187,3204031479,3329325298];
    var H = [1779033703,3144134277,1013904242,2773480762,1359893119,2600822924,528734635,1541459225];
    var bytes = [];
    for (var i=0;i<str.length;i++){var c=str.charCodeAt(i);if(c<0x80)bytes.push(c);else if(c<0x800){bytes.push(0xc0|(c>>6),0x80|(c&0x3f));}else if(c<0xd800||c>=0xe000){bytes.push(0xe0|(c>>12),0x80|((c>>6)&0x3f),0x80|(c&0x3f));}else{i++;c=0x10000+(((c&0x3ff)<<10)|(str.charCodeAt(i)&0x3ff));bytes.push(0xf0|(c>>18),0x80|((c>>12)&0x3f),0x80|((c>>6)&0x3f),0x80|(c&0x3f));}}
    var bitLen = bytes.length*8;
    bytes.push(0x80);
    while(bytes.length%64!==56)bytes.push(0);
    for(var i=0;i<8;i++){bytes.push((bitLen>>>((7-i)*8))&0xff);}
    for(var chunkStart=0;chunkStart<bytes.length;chunkStart+=64){
        var w=[];for(var i=0;i<16;i++){w[i]=(bytes[chunkStart+i*4]<<24)|(bytes[chunkStart+i*4+1]<<16)|(bytes[chunkStart+i*4+2]<<8)|bytes[chunkStart+i*4+3];}
        for(var i=16;i<64;i++){var s0=(S(w[i-15],7)^S(w[i-15],18)^(w[i-15]>>>3)),s1=(S(w[i-2],17)^S(w[i-2],19)^(w[i-2]>>>10));w[i]=w[i-16]+s0+w[i-7]+s1;}
        var a=H[0],b=H[1],c=H[2],d=H[3],e=H[4],f=H[5],g=H[6],h=H[7];
        for(var i=0;i<64;i++){var S1=(S(e,6)^S(e,11)^S(e,25)),ch=(e&f)^((~e)&g),t1=h+S1+ch+K[i]+w[i],S0=(S(a,2)^S(a,13)^S(a,22)),maj=(a&b)^(a&c)^(b&c),t2=S0+maj;h=g;g=f;f=e;e=d+t1;d=c;c=b;b=a;a=t1+t2;}
        H[0]=H[0]+a;H[1]=H[1]+b;H[2]=H[2]+c;H[3]=H[3]+d;H[4]=H[4]+e;H[5]=H[5]+f;H[6]=H[6]+g;H[7]=H[7]+h;
    }
    var hex='';
    for(var i=0;i<8;i++)hex+=((H[i]>>>0).toString(16).padStart(8,'0'));
    return hex;
}
```

- [ ] **Step 2: 修改 doLogin() 发送 transHash 而非 password**

找到 `body: JSON.stringify({username:u, password:p})`，改为：

```javascript
body: JSON.stringify({username: u, transHash: sha256(p)})
```

- [ ] **Step 3: 修改 doRegister() 同理**

找到 `body:JSON.stringify({username:u, password:p, role:role})`，改为：

```javascript
body: JSON.stringify({username: u, transHash: sha256(p), role: role})
```

- [ ] **Step 4: 添加 loadConfig() 和 authServer 全局变量**

在 `function getToken()` 之前插入：

```javascript
var authServer = 'http://localhost:8890';  // 默认值，可由 /api/config 覆盖
var MACHINE_ID = '主';                      // 默认值

async function loadConfig() {
    try {
        var r = await fetch('/api/config');
        var cfg = await r.json();
        if (cfg.authServer) authServer = cfg.authServer;
        if (cfg.machine) MACHINE_ID = cfg.machine;
    } catch(e) { /* 保持默认值 localhost */ }
}
```

- [ ] **Step 5: 替换所有硬编码的 AuthServer URL**

`doLogin()` 中 `fetch('http://localhost:8890/api/auth/login',` → `fetch(authServer + '/api/auth/login',`
`doRegister()` 中 `fetch('http://localhost:8890/api/auth/register',` → `fetch(authServer + '/api/auth/register',`

- [ ] **Step 6: 在 routeGuard() 开头调用 loadConfig()**

```javascript
function routeGuard() {
    loadConfig();  // 获取 authServer 地址
    // ... 其余不变
}
```

- [ ] **Step 7: 编译并提交**

```bash
git add display/src/main/resources/web/index.html
git commit -m "feat: 前端 SHA-256 传输 + authServer 地址发现"
```

---

### Task 3: AuthServer 两层哈希 + IP 限制

**Files:**
- Modify: `auth/src/main/java/inspection/auth/AuthServerMain.java`

**Interfaces:**
- Consumes: 前端发送 `transHash` 字段（原 `password` 字段）
- Produces: LoginHandler/RegisterHandler 改为接收 `transHash`

- [ ] **Step 1: 修改 AuthServerMain.main() — 清旧格式在前面**

找到 `pool = new JedisPool(...)` 之后的几行，在 `for (int i = 0; ...)` 循环之前插入：

```java
// 清除旧格式哈希（salt+SHA-256(明文) → 改为 salt+SHA-256(SHA-256(明文))）
try (Jedis jedis = pool.getResource()) {
    jedis.del("auth:user:admin");
} catch (Exception ignored) {}
```

- [ ] **Step 2: 修改 RegisterHandler — password → transHash**

找到 `String password = body.getString("password");`，改为：

```java
String password = body.getString("transHash");
```

找到错误消息中的 `"参数不完整（需要 username, password, role）"`，改为：

```java
"参数不完整（需要 username, transHash, role）"
```

- [ ] **Step 3: 修改 LoginHandler — password → transHash**

找到 `String password = body.getString("password");`，改为：

```java
String password = body.getString("transHash");
```

找到错误消息 `"请输入用户名和密码"`，改为：

```java
"请输入用户名和 transHash"
```

- [ ] **Step 4: 修改 LoginHandler — 加配置员 IP 限制**

在登录成功、创建 token 之前（`if (user == null)` 检查之后），插入：

```java
// 配置员仅限本机登录
if (user.getRole() == Role.CONFIGURATOR) {
    String ip = exchange.getRemoteAddress().getAddress().getHostAddress();
    boolean isLocal = "127.0.0.1".equals(ip)
                   || "0:0:0:0:0:0:0:1".equals(ip)
                   || "::1".equals(ip);
    if (!isLocal) {
        send(ex, 403, error("配置员仅限主公机本机登录"));
        return;
    }
}
```

- [ ] **Step 5: 编译并提交**

```bash
git add auth/src/main/java/inspection/auth/AuthServerMain.java
git commit -m "feat: AuthServer 两层 SHA-256 + 配置员 IP 限制"
```

---

### Task 4: WebSocket AUTH 认证

**Files:**
- Modify: `display/src/main/java/inspection/display/CommandReceiver.java`
- Modify: `display/src/main/java/inspection/display/DisplayMain.java` (传递 machineId)
- Modify: `display/src/main/resources/web/index.html`

**Interfaces:**
- Produces: `CommandReceiver.ConnState` inner class; `onOpen`/`onClose` manage connStates; AUTH message handler

- [ ] **Step 1: DisplayMain 把 machine 传给 CommandReceiver**

在 `DisplayMain.main()` 中，解析 `--machine` 参数后，传给 CommandReceiver：

```java
// 在现有参数解析后添加
ArgsParser args = new ArgsParser(args);  // ... 参数解析稍后在 Task 14 完整做
String machineId = args.get("--machine", "主");

// 在 wsServer 创建之后，start 之前
wsServer.setMachineId(machineId);
```

当前 DisplayMain 先用最小改动——先只传 machineId，完整参数列表在 Task 14 做。

- [ ] **Step 2: CommandReceiver 添加 ConnState 和 machineId 字段**

```java
// 在类顶部添加 import
import java.util.concurrent.ConcurrentHashMap;

// 在 CommandReceiver 类内部添加
private final Map<WebSocket, ConnState> connStates = new ConcurrentHashMap<>();
private String machineId = "主";

public void setMachineId(String machineId) {
    this.machineId = machineId;
}

static class ConnState {
    String username;
    String role;
    String machineId;
}
```

- [ ] **Step 3: 重写 onOpen 和 onClose**

```java
@Override
public void onOpen(WebSocket conn, ClientHandshake handshake) {
    connStates.put(conn, new ConnState());  // username=null，标记为未认证
    LOG.info("浏览器已连接: {}, 当前连接数: {}",
            conn.getRemoteSocketAddress(), getConnections().size());
}

@Override
public void onClose(WebSocket conn, int code, String reason, boolean remote) {
    connStates.remove(conn);
    LOG.info("浏览器已断开: {}, 当前连接数: {}",
            conn.getRemoteSocketAddress(), getConnections().size());
}
```

- [ ] **Step 4: 修改 onMessage — 第一条消息必须是 AUTH**

在 `onMessage` 开头，switch 之前插入：

```java
@Override
public void onMessage(WebSocket conn, String message) {
    LOG.debug("收到浏览器消息: {}", message);
    try {
        JSONObject json = JSON.parseObject(message);
        String type = json.getString("type");

        // ── AUTH 认证检查 ──
        ConnState state = connStates.get(conn);
        if (state == null) {
            conn.close(4001, "未注册连接");
            return;
        }
        if (state.username == null) {
            // 第一条消息必须是 AUTH
            if (!"AUTH".equals(type)) {
                conn.close(4001, "请先认证");
                return;
            }
            handleAuth(conn, json);
            return;
        }

        // 后续消息正常处理 ... (现有 switch 保留)
        switch (type) {
```

- [ ] **Step 5: 添加 handleAuth 方法**

```java
private void handleAuth(WebSocket conn, JSONObject json) {
    String token = json.getString("token");
    if (token == null || token.isEmpty()) {
        conn.close(4001, "缺少认证令牌");
        return;
    }

    // 调用 AuthServer 验证 token（URL 由 Display 的 --auth-host 参数决定）
    try {
        String authHost = System.getProperty("auth.host", "localhost");
        int authPort = Integer.parseInt(System.getProperty("auth.port", "8890"));
        java.net.URI uri = new java.net.URI("http", null, authHost, authPort,
                "/api/auth/verify", "token=" + token, null);
        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(uri).GET().build();
        java.net.http.HttpResponse<String> resp = client.send(req,
                java.net.http.HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            conn.close(4001, "认证失败");
            return;
        }
        com.alibaba.fastjson2.JSONObject verifyResp =
                com.alibaba.fastjson2.JSON.parseObject(resp.body());
        if (!verifyResp.getBooleanValue("success", false)) {
            conn.close(4001, "认证失败");
            return;
        }

        ConnState state = connStates.get(conn);
        state.username = verifyResp.getString("username");
        state.role = verifyResp.getString("role");
        state.machineId = machineId;

        // 回复 AUTH_OK，告知前端 machine
        com.alibaba.fastjson2.JSONObject ok = new com.alibaba.fastjson2.JSONObject();
        ok.put("type", "AUTH_OK");
        ok.put("machine", machineId);
        conn.send(ok.toJSONString());

        LOG.info("认证成功: username={}, role={}, machineId={}",
                state.username, state.role, state.machineId);
    } catch (Exception e) {
        LOG.error("AUTH 验证失败", e);
        conn.close(4001, "认证服务不可用");
    }
}
```

- [ ] **Step 6: 前端 ws.onopen 发送 AUTH**

在 `index.html` 的 `connectWebSocket()` 函数中，`ws.onopen` 里追加：

```javascript
ws.onopen = function(){
    // ... 现有代码 ...
    // 发送认证消息
    ws.send(JSON.stringify({type: 'AUTH', token: getToken()}));
};
```

- [ ] **Step 7: 前端存 MACHINE_ID**

在 `connectWebSocket()` 的 `ws.onmessage` 中，处理 AUTH_OK：

```javascript
ws.onmessage = function(event){
    try {
        var msg = JSON.parse(event.data);
        if (msg.type === 'AUTH_OK') {
            MACHINE_ID = msg.machine;
            return;
        }
        currentState = msg;
        render(msg);
    } catch(e) { console.error(e); }
};
```

- [ ] **Step 8: DisplayMain 设置 auth host/port 系统属性**

在 `DisplayMain.main()` 中，参数解析后：

```java
System.setProperty("auth.host", args.get("--auth-host", "localhost"));
System.setProperty("auth.port", String.valueOf(args.getInt("--auth-port", 8890)));
```

- [ ] **Step 9: 编译并提交**

```bash
git add display/src/main/java/inspection/display/CommandReceiver.java display/src/main/java/inspection/display/DisplayMain.java display/src/main/resources/web/index.html
git commit -m "feat: WebSocket AUTH 认证 + AUTH_OK 回复 machineId"
```

---

### Task 5: 后端权限校验

**Files:**
- Modify: `display/src/main/java/inspection/display/CommandReceiver.java`

**Interfaces:**
- Consumes: `ConnState.role` (from Task 4), `PermissionManager.DEFAULT_PERMISSIONS`

- [ ] **Step 1: 添加 checkPermission 方法（内联权限，避免跨模块依赖）**

```java
// 内联权限常量（与 auth/PermissionManager.DEFAULT_PERMISSIONS 保持同步）
private static final Set<String> CONFIG_ONLY = Set.of("SET_CONFIG", "RESET", "TOGGLE_OBSTACLE", "RECORD_START", "RECORD_STOP");
private static final Set<String> CONFIG_OR_OP = Set.of("START", "PAUSE", "ADD_CAR", "REMOVE_CAR");

private boolean checkPermission(WebSocket conn, String action) {
    ConnState state = connStates.get(conn);
    if (state == null || state.role == null) return false;
    String role = state.role;
    if ("configurator".equals(role)) return true;  // 配置员所有权限
    if ("operator".equals(role)) {
        if (CONFIG_OR_OP.contains(action)) return true;
    }
    // analyst 只能查看
    try {
        conn.send("{\"type\":\"ERROR\",\"message\":\"权限不足\"}");
    } catch (Exception e) { /* ignore */ }
    return false;
}
```

- [ ] **Step 2: 在 onMessage 的 switch 之前，对需要权限的命令加检查**

在 `switch (type)` 之前的 AUTH 检查之后，插入权限检查：

```java
        // ── 权限校验（AUTH 通过后） ──
        // 以下命令需要特定角色
        if ("SET_CONFIG".equals(type) || "RESET".equals(type) || "TOGGLE_OBSTACLE".equals(type)
                || "RECORD_START".equals(type) || "RECORD_STOP".equals(type)) {
            if (!checkPermission(conn, type)) return;
        }
        if ("START".equals(type) || "PAUSE".equals(type) || "ADD_CAR".equals(type) || "REMOVE_CAR".equals(type)) {
            if (!checkPermission(conn, type)) return;
        }

        switch (type) {
```

- [ ] **Step 3: 编译并提交**

```bash
git add display/src/main/java/inspection/display/CommandReceiver.java
git commit -m "feat: CommandReceiver 后端权限校验"
```

---

### Task 6: BlackboardClient 新增暂停/归属方法

**Files:**
- Modify: `common/src/main/java/inspection/common/client/BlackboardClient.java`

**Interfaces:**
- Produces: `setCarOwner/getCarOwner`, `setGlobalPause/isGlobalPaused`, `setOperatorPause/isOperatorPaused`

- [ ] **Step 1: 在类的末尾（close() 方法之前）添加 6 个新方法**

```java
// ==================== 多机归属与暂停 ====================

public void setCarOwner(String carId, String machineId) {
    try (Jedis jedis = pool.getResource()) {
        jedis.set("car:" + carId + ":owner", machineId);
    }
}

public String getCarOwner(String carId) {
    try (Jedis jedis = pool.getResource()) {
        return jedis.get("car:" + carId + ":owner");
    }
}

public void setGlobalPause(boolean paused) {
    try (Jedis jedis = pool.getResource()) {
        if (paused) {
            jedis.set("pause:global", "true");
        } else {
            jedis.del("pause:global");
        }
    }
}

public boolean isGlobalPaused() {
    try (Jedis jedis = pool.getResource()) {
        return jedis.exists("pause:global");
    }
}

public void setOperatorPause(String machineId, boolean paused) {
    try (Jedis jedis = pool.getResource()) {
        String key = "pause:operator:" + machineId;
        if (paused) {
            jedis.set(key, "true");
        } else {
            jedis.del(key);
        }
    }
}

public boolean isOperatorPaused(String machineId) {
    try (Jedis jedis = pool.getResource()) {
        return jedis.exists("pause:operator:" + machineId);
    }
}
```

- [ ] **Step 2: 编译并提交**

```bash
git add common/src/main/java/inspection/common/client/BlackboardClient.java
git commit -m "feat: BlackboardClient 新增暂停/归属方法"
```

---

### Task 7: ControllerAgent 分权调度

**Files:**
- Modify: `controller/src/main/java/inspection/controller/ControllerAgent.java`

**Interfaces:**
- Consumes: `BlackboardClient.isGlobalPaused()`, `BlackboardClient.isOperatorPaused(machineId)`, `BlackboardClient.getCarOwner(carId)`

- [ ] **Step 1: 修改 tickDriveCars() — 跳过被暂停的车的 MOVE_STEP**

在 `tickDriveCars()` 中，`for (String carId : carIds)` 循环体内，找到 `if (bb.getCarStatus(carId) != CarStatus.IDLE) continue;` 这行之后，插入暂停检查：

```java
// 每个 tick 推进所有 IDLE 且有剩余路径的小车（每车每 tick 最多 1 步）
private void tickDriveCars() {
    // 全局暂停检查
    boolean globalPaused = bb.isGlobalPaused();
    
    List<String> carIds = getAllCarIds();
    for (String carId : carIds) {
        if (!isMyCar(carIds, carId)) continue;
        try {
            if (bb.getCarStatus(carId) != CarStatus.IDLE) continue;
            
            // ── 暂停检查 ──
            if (globalPaused) continue;
            String owner = bb.getCarOwner(carId);
            if (owner != null && !owner.isEmpty() && bb.isOperatorPaused(owner)) {
                continue;  // 该运行员暂停了自己的车
            }
            
            Point next = bb.peekNextStep(carId);
            if (next == null) continue;
            // ... 其余不变
```

- [ ] **Step 2: 同样在 processTask 的 MOVE_READY 分支添加暂停检查**

在 `handlePauseTask()` 方法附近，将 `MOVE_READY` 分支包裹暂停检查：

```java
case "MOVE_READY":
    // ── 暂停检查 ──
    if (bb.isGlobalPaused()) {
        log.debug("全局暂停中，跳过 carId={}", carId);
        break;
    }
    String owner = bb.getCarOwner(carId);
    if (owner != null && !owner.isEmpty() && bb.isOperatorPaused(owner)) {
        log.debug("运行员 {} 暂停中，跳过 carId={}", owner, carId);
        break;
    }
    log.info("🚗 [MOVE_READY] 发 MOVE_STEP → Car:{}, carId={}", carId);
    JSONObject moveData = new JSONObject();
    moveData.put("carId", carId);
    sendCommand(CommandType.MOVE_STEP, moveData, ConfigConstants.carQueueName(carId));
    break;
```

- [ ] **Step 3: 修改 handlePauseTask() 区分全局/个人**

现有 `handlePauseTask()` 只处理全局暂停。改为不再无条件设置 taskActive=false：

```java
private void handlePauseTask() {
    // 全局暂停由 CommandReceiver 通过 BlackboardClient.setGlobalPause(true) 直接操作 Redis
    // ControllerAgent 只需更新本地 taskActive 以阻止任务处理循环
    log.info("⏸ Pause: 停用任务处理器");
    userActivated = false;
    taskActive = false;
}
```

保留现有逻辑，额外加注释说明全局暂停已在 CommandReceiver 层设置 `pause:global` key。

- [ ] **Step 4: 修改 broadcastTick() — 全局暂停时跳过 tickDrive**

在 `broadcastTick()` 中：

```java
if (taskActive) {
    // 全局暂停时跳过 tickDrive，但仍继续广播（让 Display 看到暂停状态）
    if (!bb.isGlobalPaused()) {
        tickDriveCars();
    }
    // 其余 logic (fallbackBlockedCheck, tickCount++, saveSnapshot) 保留
    fallbackBlockedCheck();
    tickCount++;
    saveSnapshotIfRecording();
    if (tickCount % 20 == 0) { ... }
}
```

- [ ] **Step 5: 编译并提交**

```bash
git add controller/src/main/java/inspection/controller/ControllerAgent.java
git commit -m "feat: Controller 支持全局暂停 + 运行员个人暂停"
```

---

### Task 8: CommandReceiver scope 分发

**Files:**
- Modify: `display/src/main/java/inspection/display/CommandReceiver.java`

**Interfaces:**
- Consumes: `BlackboardClient` 的 `setGlobalPause`/`setOperatorPause`/`isGlobalPaused` 方法

- [ ] **Step 1: 修改 handlePause(JSONObject json, ConnState state) — 支持 scope 字段**

改方法签名加 ConnState 参数，修改 onMessage 中的调用处 `handlePause()` → `handlePause(json, state)`：

```java
private void handlePause(JSONObject json, ConnState state) {
    String scope = json.getString("scope");
    if ("personal".equals(scope)) {
        String machineId = state != null ? state.machineId : null;
        if (machineId == null) {
            LOG.warn("PAUSE personal 但 machineId 为空");
            return;
        }
        blackboard.setOperatorPause(machineId, true);
        LOG.info("运行员 {} 暂停了自己的车", machineId);
    } else {
        // 默认全局暂停
        blackboard.setGlobalPause(true);
        blackboard.setTaskActive(false);
        LOG.info("全局暂停");
    }
}
```

- [ ] **Step 2: 修改 handleStart(JSONObject json, ConnState state) — 支持 scope 字段**

同理改签名：

```java
private void handleStart(JSONObject json, ConnState state) {
    String scope = json.getString("scope");
    if ("personal".equals(scope)) {
        String machineId = state != null ? state.machineId : null;
        if (machineId == null) {
            LOG.warn("START personal 但 machineId 为空");
            return;
        }
        if (blackboard.isGlobalPaused()) {
            LOG.warn("运行员 {} 尝试恢复但全局暂停中", machineId);
            return;
        }
        blackboard.setOperatorPause(machineId, false);
        LOG.info("运行员 {} 恢复了自己的车", machineId);
    } else {
        // 默认全局开始
        blackboard.setGlobalPause(false);
        blackboard.setTaskActive(true);
        LOG.info("全局开始");
    }
}
```

- [ ] **Step 3: 修改 onMessage switch 中 START/PAUSE 的调用方式**

在 `onMessage` 的 switch 中：

```java
                case "START":
                    handleStart(json, state);
                    break;
                case "PAUSE":
                    handlePause(json, state);
                    break;
```

- [ ] **Step 4: 修改 handleAddCar(JSONObject, ConnState) — 添加 owner 写入**

```java
private void handleAddCar(JSONObject json, ConnState state) {
    // ... 现有逻辑获取 carId, x, y ...
    // 新增：写入 car owner
    String machine = json.getString("machine");
    if (machine != null && !machine.isEmpty()) {
        blackboard.setCarOwner(carId, machine);
    }
    // ... 其余 Forward to TaskConfigurator 逻辑不变 ...
}
```

在向 TaskConfigurator 发送 FORWARD_CONFIG 的数据中也添加：

```java
data.put("machine", machine);
```

同时在 `onMessage` 的 switch 中把 `handleAddCar(json)` 改为 `handleAddCar(json, state)`。

- [ ] **Step 4: 编译并提交**

```bash
git add display/src/main/java/inspection/display/CommandReceiver.java
git commit -m "feat: CommandReceiver scope 分发 + ADD_CAR 带 owner"
```

---

### Task 9: TaskConfigurator ADD_CAR 写入 owner

**Files:**
- Modify: `task-configurator/src/main/java/inspection/taskconfigurator/TaskConfiguratorMain.java`

- [ ] **Step 1: 在 ADD_CAR 分支写入 car owner**

在 `handleForwardConfig()` 的 ADD_CAR 分支，找到 `blackboard.addCar(carId, cx, cy)` 之后，添加：

```java
if (addCar) {
    // ... 现有 addCar 逻辑 ...
    String machine = data.getString("machine");
    if (machine != null && !machine.isEmpty()) {
        blackboard.setCarOwner(carId, machine);
    }
    return;
}
```

- [ ] **Step 2: 编译并提交**

```bash
git add task-configurator/src/main/java/inspection/taskconfigurator/TaskConfiguratorMain.java
git commit -m "feat: TaskConfigurator ADD_CAR 写入 car owner"
```

---

### Task 10: 配置员面板（前端）

**Files:**
- Modify: `display/src/main/resources/web/index.html`

- [ ] **Step 1: 更新 buildPanel('configurator') — 按钮文案**

找到 `buildPanel` 中 `role === 'configurator'` 分支，按钮区域改为：

```javascript
section('全局控制（最高权限）',[
    '<div class="btn-row"><button class="btn btn-start" id="btnStart" onclick="sendCmd(\'START\', \'global\')">▶ 全局开始</button><button class="btn btn-pause" id="btnPause" onclick="sendCmd(\'PAUSE\', \'global\')">⏸ 全局暂停</button><button class="btn btn-reset" id="btnReset" onclick="sendCmd(\'RESET\', \'global\')">↺ 全局重置</button></div>',
    '<button class="btn btn-full" onclick="addCar()" style="margin-top:8px;background:linear-gradient(135deg,#7c3aed,#a78bfa)">➕ 添加小车</button>'
])
```

- [ ] **Step 2: 更新 sendCmd() 支持 scope 参数**

```javascript
function sendCmd(type, scope) {
    if (!ws || ws.readyState !== WebSocket.OPEN) { alert('WebSocket未连接!'); return; }
    var msg = { type: type, scope: scope || 'global' };
    if (type === 'RESET') {
        msg.mapWidth = parseInt((document.getElementById('mapWidth')||{}).value) || 40;
        msg.mapHeight = parseInt((document.getElementById('mapHeight')||{}).value) || 40;
        msg.carCount = parseInt((document.getElementById('carCount')||{}).value) || 4;
        msg.obstacleDensity = parseFloat((document.getElementById('obstacleDensity')||{}).value) || 0.1;
    }
    ws.send(JSON.stringify(msg));
}
```

- [ ] **Step 3: 全局暂停横幅**

在 render() 函数中，配置员面板添加：

```javascript
// 全局暂停横幅（仅配置员可见）
var pauseBanner = document.getElementById('globalPauseBanner');
if (getRole() === 'configurator') {
    if (state.globalPaused) {
        if (!pauseBanner) {
            pauseBanner = document.createElement('div');
            pauseBanner.id = 'globalPauseBanner';
            pauseBanner.style.cssText = 'text-align:center;padding:8px;background:rgba(248,113,113,.12);color:var(--red);border-radius:8px;margin-bottom:10px;font-weight:700;font-size:13px';
            var panel = document.getElementById('panelContent');
            panel.insertBefore(pauseBanner, panel.firstChild);
        }
        pauseBanner.textContent = '⏸ 全局已暂停 — 所有运行员无法操作';
        pauseBanner.style.display = '';
    } else {
        if (pauseBanner) pauseBanner.style.display = 'none';
    }
}
```

- [ ] **Step 4: 用户列表**

在配置员面板的"用户管理"section 中，添加用户列表：

```javascript
section('用户管理',[
    '<div class="reg-row"><input type="text" id="regUser" placeholder="用户名"><input type="password" id="regPass" placeholder="密码"><select id="regRole"><option value="operator">运行员</option><option value="analyst">分析员</option></select></div>',
    '<button class="btn btn-full" onclick="doRegister()" style="background:linear-gradient(135deg,#2563eb,#6366f1)">➕ 创建用户</button>',
    '<div class="reg-msg" id="regMsg"></div>',
    '<table class="user-table" id="userTable"><tbody><tr><td style="color:var(--text3)">加载中...</td></tr></tbody></table>'
])
```

用户列表由后端 state 推送（StateBroadcaster 在 REFRESH_ALL 中包含用户列表），或配置员面板主动从 AuthServer 拉取。

- [ ] **Step 5: 添加"随机生成障碍物"按钮**

在"障碍物编辑"section 增加：

```javascript
'<button class="btn btn-full" onclick="ws.send(JSON.stringify({type:\'RANDOM_OBSTACLES\'}))" style="background:linear-gradient(135deg,#d97706,#f59e0b);color:#1a1a1a">🎲 随机生成障碍物</button>'
```

- [ ] **Step 6: 编译并提交**

```bash
git add display/src/main/resources/web/index.html
git commit -m "feat: 配置员面板 — 全局控制 + 全局暂停横幅 + 用户列表"
```

---

### Task 11: 运行员面板（前端）

**Files:**
- Modify: `display/src/main/resources/web/index.html`

- [ ] **Step 1: 更新 buildPanel('operator') — 完整面板**

```javascript
if (role === 'operator') {
    p.innerHTML =
        '<div id="globalPauseBanner" style="display:none;text-align:center;padding:8px;background:rgba(248,113,113,.12);color:var(--red);border-radius:8px;margin-bottom:10px;font-weight:700;font-size:13px">⏸ 配置员已全局暂停 — 所有操作已锁定</div>' +
        section('我的控制',[
            '<div class="btn-row"><button class="btn btn-start" id="btnMyStart" onclick="sendCmd(\'START\',\'personal\')">▶ 恢复我的车</button><button class="btn btn-pause" id="btnMyPause" onclick="sendCmd(\'PAUSE\',\'personal\')">⏸ 暂停我的车</button></div>',
            '<button class="btn btn-full" onclick="addMyCar()" style="margin-top:8px;background:linear-gradient(135deg,#7c3aed,#a78bfa)">➕ 添加我的新车</button>',
            '<div style="font-size:10px;color:var(--text3);margin-top:8px">你只能控制自己的小车。配置员全局暂停时所有操作锁定。</div>'
        ]) +
        section('运行状态',[
            '<div style="display:flex;justify-content:space-between;margin-bottom:4px"><span style="font-size:11px;color:var(--text3);font-weight:600">全局探索率</span><span id="taskStatus" class="task-inactive">未激活</span></div>',
            '<div style="display:flex;align-items:center;gap:12px;margin-bottom:10px"><div class="explore-bar-outer"><div class="explore-bar-inner" id="exploreBar" style="width:0%"></div></div><span class="explore-value" id="exploreRate">0.0%</span></div>',
            '<div style="font-size:11px;color:var(--text2);margin-bottom:6px;font-weight:600">🚗 我的小车（机器：<span id="myMachineLabel"></span>）</div>',
            '<table class="car-table"><thead><tr><th>小车</th><th>状态</th><th>位置</th><th>步数</th></tr></thead><tbody id="carTableBody"><tr><td colspan="4" style="text-align:center;color:var(--text3);padding:12px">等待数据...</td></tr></tbody></table>',
            '<div class="completed-badge" id="completedBadge">🎉 巡检完成！</div>'
        ]) +
        section('图例',[legendHtml()]);
    document.getElementById('myMachineLabel').textContent = MACHINE_ID;
}
```

- [ ] **Step 2: 修改 render() — 运行员模式筛选我的小车**

在 render() 函数的 car table 更新区域，只显示自己的车：

```javascript
// Car table 更新 — 运行员模式只显示自己的车
var tbody = document.getElementById('carTableBody');
if (tbody) {
    var carList = state.cars || [];
    if (getRole() === 'operator') {
        carList = carList.filter(function(c) {
            return c.owner === MACHINE_ID;
        });
    }
    // ... 其余 table 更新逻辑不变
}
```

- [ ] **Step 3: 修改 render() — 运行员模式按钮状态**

```javascript
// 运行员模式下按钮状态
var bs = document.getElementById('btnMyStart'), bp = document.getElementById('btnMyPause');
if (bs && bp) {
    var globalPaused = state.globalPaused;
    bs.disabled = globalPaused;
    bp.disabled = globalPaused;
    if (globalPaused) {
        document.getElementById('globalPauseBanner').style.display = '';
    } else {
        document.getElementById('globalPauseBanner').style.display = 'none';
    }
}
```

- [ ] **Step 4: addMyCar() — 带 machine 字段**

```javascript
function addMyCar() {
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    var n = 1;
    ws.send(JSON.stringify({type:'ADD_CAR', machine: MACHINE_ID,
        x: Math.floor(Math.random()*30)+5, y: Math.floor(Math.random()*30)+5}));
}
```

- [ ] **Step 5: StateBroadcaster 推送 globalPaused + car owner**

需要后端 `StateBroadcaster` 在 REFRESH_ALL 广播中加入 `globalPaused` 字段和每辆车的 `owner`。这将在后续 Task 中处理，但前端需要解析这些字段。

- [ ] **Step 6: 编译并提交**

```bash
git add display/src/main/resources/web/index.html
git commit -m "feat: 运行员面板 — 我的小车 + personal 暂停/恢复"
```

---

### Task 12: 模块参数支持（Car / Navigator / Controller / TaskCfg / TargetPlanner）

**Files:**
- Modify: `car/.../CarMain.java`
- Modify: `navigator/.../NavigatorMain.java`
- Modify: `controller/.../ControllerMain.java`
- Modify: `task-configurator/.../TaskConfiguratorMain.java`
- Modify: `target-planner/.../TargetPlannerMain.java`

- [ ] **Step 1: CarMain — `--rabbit-host` → `--mq-host`**

在 `CarMain.main()` 中，将第20-26行的 switch 中的参数名修改：

```java
case "--mq-host": rabbitHost = args[++i]; break;
case "--mq-port": rabbitPort = Integer.parseInt(args[++i]); break;
```

删除原本的 `--rabbit-host` 和 `--rabbit-port` 的两个 case。

- [ ] **Step 2: NavigatorMain — 添加参数解析**

在 `NavigatorMain.main()` 开头添加，`start()` 方法签名改为 `start(String[] args)`，在方法开头解析：

```java
public static void main(String[] args) throws Exception {
    new NavigatorMain().start(args);
}

public void start(String[] args) throws Exception {
    ArgsParser argsParser = new ArgsParser(args);
    String redisHost = argsParser.get("--redis-host", ConfigConstants.REDIS_HOST);
    int redisPort = argsParser.getInt("--redis-port", ConfigConstants.REDIS_PORT);
    String mqHost = argsParser.get("--mq-host", ConfigConstants.RABBITMQ_HOST);
    int mqPort = argsParser.getInt("--mq-port", ConfigConstants.RABBITMQ_PORT);

    blackboard = new BlackboardClient(redisHost, redisPort);
    messageBus = new MessageBusClient(mqHost, mqPort,
            ConfigConstants.RABBITMQ_USER, ConfigConstants.RABBITMQ_PASS, ConfigConstants.RABBITMQ_VHOST);
    // ... 其余不变
```

- [ ] **Step 3: ControllerMain — 添加参数解析**

在 `ControllerMain.main()` 开头，创建连接之前添加：

```java
ArgsParser argsParser = new ArgsParser(args);
String redisHost = argsParser.get("--redis-host", ConfigConstants.REDIS_HOST);
int redisPort = argsParser.getInt("--redis-port", ConfigConstants.REDIS_PORT);
String mqHost = argsParser.get("--mq-host", ConfigConstants.RABBITMQ_HOST);
int mqPort = argsParser.getInt("--mq-port", ConfigConstants.RABBITMQ_PORT);

instanceId = argsParser.getInt("--instance-id", 0);
totalInstances = argsParser.getInt("--total-instances", 1);

BlackboardClient blackboard = new BlackboardClient(redisHost, redisPort);
MessageBusClient messageBus = new MessageBusClient(mqHost, mqPort,
        ConfigConstants.RABBITMQ_USER, ConfigConstants.RABBITMQ_PASS, ConfigConstants.RABBITMQ_VHOST);
```

注意需要把 `instanceId` 和 `totalInstances` 的声明移到 main 开头。

- [ ] **Step 4: TaskConfiguratorMain — 添加参数解析**

在 `TaskConfiguratorMain.start()` 方法开头：

```java
public void start(String[] args) throws Exception {
    ArgsParser argsParser = new ArgsParser(args);
    String redisHost = argsParser.get("--redis-host", ConfigConstants.REDIS_HOST);
    int redisPort = argsParser.getInt("--redis-port", ConfigConstants.REDIS_PORT);
    String mqHost = argsParser.get("--mq-host", ConfigConstants.RABBITMQ_HOST);
    int mqPort = argsParser.getInt("--mq-port", ConfigConstants.RABBITMQ_PORT);

    blackboard = new BlackboardClient(redisHost, redisPort);
    messageBus = new MessageBusClient(mqHost, mqPort,
            ConfigConstants.RABBITMQ_USER, ConfigConstants.RABBITMQ_PASS, ConfigConstants.RABBITMQ_VHOST);
    // ... 其余不变
```

同时改 main()：

```java
public static void main(String[] args) throws Exception {
    new TaskConfiguratorMain().start(args);
}
```

- [ ] **Step 5: TargetPlannerMain — 添加参数解析**

同理在 `TargetPlannerMain.start()` 方法开头：

```java
public void start(String[] args) throws Exception {
    ArgsParser argsParser = new ArgsParser(args);
    String redisHost = argsParser.get("--redis-host", ConfigConstants.REDIS_HOST);
    int redisPort = argsParser.getInt("--redis-port", ConfigConstants.REDIS_PORT);
    String mqHost = argsParser.get("--mq-host", ConfigConstants.RABBITMQ_HOST);
    int mqPort = argsParser.getInt("--mq-port", ConfigConstants.RABBITMQ_PORT);

    blackboard = new BlackboardClient(redisHost, redisPort);
    messageBus = new MessageBusClient(mqHost, mqPort,
            ConfigConstants.RABBITMQ_USER, ConfigConstants.RABBITMQ_PASS, ConfigConstants.RABBITMQ_VHOST);
    // ... 其余不变
```

改 main()：

```java
public static void main(String[] args) throws Exception {
    new TargetPlannerMain().start(args);
}
```

- [ ] **Step 6: 编译并提交**

```bash
git add car/src/main/java/inspection/car/CarMain.java navigator/src/main/java/inspection/navigator/NavigatorMain.java controller/src/main/java/inspection/controller/ControllerMain.java task-configurator/src/main/java/inspection/taskconfigurator/TaskConfiguratorMain.java target-planner/src/main/java/inspection/targetplanner/TargetPlannerMain.java
git commit -m "feat: 5个模块支持 --redis-host/port --mq-host/port 参数"
```

---

### Task 13: AuthServer + ReplayServer 参数化

**Files:**
- Modify: `auth/src/main/java/inspection/auth/AuthServerMain.java`
- Modify: `replay/src/main/java/inspection/replay/ReplayMain.java`

- [ ] **Step 1: AuthServer — 添加 Redis 参数**

在 `AuthServerMain.main()` 开头：

```java
public static void main(String[] args) throws Exception {
    ArgsParser argsParser = new ArgsParser(args);
    String redisHost = argsParser.get("--redis-host", ConfigConstants.REDIS_HOST);
    int redisPort = argsParser.getInt("--redis-port", ConfigConstants.REDIS_PORT);
    
    pool = new JedisPool(redisHost, redisPort);
    // ... 其余不变
```

加上 import：`import inspection.common.config.ArgsParser;`

- [ ] **Step 2: ReplayMain — 添加参数，端口改为 8893**

```java
public static void main(String[] args) throws Exception {
    ArgsParser argsParser = new ArgsParser(args);
    String redisHost = argsParser.get("--redis-host", ConfigConstants.REDIS_HOST);
    int redisPort = argsParser.getInt("--redis-port", ConfigConstants.REDIS_PORT);
    int httpPort = argsParser.getInt("--port", 8893);

    var pool = new redis.clients.jedis.JedisPool(redisHost, redisPort);
    jedis = pool.getResource();

    HttpServer server = HttpServer.create(new InetSocketAddress(httpPort), 0);
    // ... 其余不变
```

加上 import：`import inspection.common.config.ArgsParser;`

- [ ] **Step 3: 编译并提交**

```bash
git add auth/src/main/java/inspection/auth/AuthServerMain.java replay/src/main/java/inspection/replay/ReplayMain.java
git commit -m "feat: AuthServer + ReplayServer 参数化，Replay 端口 8893"
```

---

### Task 14: Display 完整参数化 + /api/config 端点

**Files:**
- Modify: `display/src/main/java/inspection/display/DisplayMain.java`

- [ ] **Step 1: 用 ArgsParser 替换现有手动参数解析**

在 `DisplayMain.main()` 开头：

```java
public static void main(String[] args) throws Exception {
    ArgsParser argsParser = new ArgsParser(args);
    String redisHost = argsParser.get("--redis-host", ConfigConstants.REDIS_HOST);
    int redisPort = argsParser.getInt("--redis-port", ConfigConstants.REDIS_PORT);
    String mqHost = argsParser.get("--mq-host", ConfigConstants.RABBITMQ_HOST);
    int mqPort = argsParser.getInt("--mq-port", ConfigConstants.RABBITMQ_PORT);
    int wsPort = argsParser.getInt("--ws-port", 8887);
    int httpPort = argsParser.getInt("--http-port", 8888);
    int carCount = argsParser.getInt("--car-count", 4);
    String machineId = argsParser.get("--machine", "主");
    String authHost = argsParser.get("--auth-host", "localhost");
    int authPort = argsParser.getInt("--auth-port", 8890);

    // 设置 auth 地址供 CommandReceiver 使用
    System.setProperty("auth.host", authHost);
    System.setProperty("auth.port", String.valueOf(authPort));
```

删掉现有的手动 for loop 解析（`for (int i = 0; i < args.length; ...）`），所有参数统一用 ArgsParser。

- [ ] **Step 2: 使用解析后的参数创建连接**

```java
    BlackboardClient bb = new BlackboardClient(redisHost, redisPort);
    MessageBusClient mq = new MessageBusClient(mqHost, mqPort,
            ConfigConstants.RABBITMQ_USER, ConfigConstants.RABBITMQ_PASS, ConfigConstants.RABBITMQ_VHOST);
```

- [ ] **Step 3: 传递 machineId 给 CommandReceiver**

```java
    CommandReceiver wsServer = new CommandReceiver(new InetSocketAddress(wsPort), mq);
    wsServer.setBlackboard(bb);
    wsServer.setMachineId(machineId);
    wsServer.start();
```

- [ ] **Step 4: 添加 /api/config HTTP 端点**

在 `httpServer.createContext("/", new StaticFileHandler());` 之后添加：

```java
    httpServer.createContext("/api/config", new ConfigHandler(machineId, authHost, authPort));
```

- [ ] **Step 5: 实现 ConfigHandler 静态内部类**

在 `StaticFileHandler` 类之后添加：

```java
    static class ConfigHandler implements HttpHandler {
        private final String machineId;
        private final String authHost;
        private final int authPort;

        ConfigHandler(String machineId, String authHost, int authPort) {
            this.machineId = machineId;
            this.authHost = authHost;
            this.authPort = authPort;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            JSONObject cfg = new JSONObject();
            cfg.put("machine", machineId);
            cfg.put("authServer", "http://" + authHost + ":" + authPort);
            byte[] bytes = cfg.toJSONString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        }
    }
```

需要加 import：`import com.alibaba.fastjson2.JSONObject;` `import java.nio.charset.StandardCharsets;`

- [ ] **Step 6: 编译并提交**

```bash
git add display/src/main/java/inspection/display/DisplayMain.java
git commit -m "feat: Display 完整参数化 + /api/config 端点"
```

---

### Task 15: SimulationState/CarState + StateBroadcaster 推送 globalPaused + car owner

**Files:**
- Modify: `common/src/main/java/inspection/common/model/SimulationState.java`
- Modify: `common/src/main/java/inspection/common/model/CarState.java`
- Modify: `display/src/main/java/inspection/display/StateBroadcaster.java`

- [ ] **Step 1: SimulationState 添加 globalPaused 字段**

在 `SimulationState.java` 中，`public boolean completed;` 之后添加：

```java
    public boolean globalPaused;                // 全局暂停状态
```

在 `setCompleted`/`isCompleted` 之后添加：

```java
    public boolean isGlobalPaused() { return globalPaused; }
    public void setGlobalPaused(boolean globalPaused) { this.globalPaused = globalPaused; }
```

- [ ] **Step 2: CarState 添加 owner 字段**

在 `CarState.java` 中，`public long blockedTick;` 之后添加：

```java
    public String owner;                        // 归属 machineId (主/B/C/D/E)
```

在 `setBlockedTick`/`getBlockedTick` 之后添加：

```java
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
```

- [ ] **Step 3: StateBroadcaster.buildState() 设置 globalPaused**

在 `buildState()` 方法中，`state.setCompleted(...)` 之前添加：

```java
        state.setGlobalPaused(blackboard.isGlobalPaused());
```

在每辆车的 `CarState` 组装循环中，`cs.setBlockedTick(...)` 之后添加：

```java
            cs.setOwner(blackboard.getCarOwner(carId));
```

- [ ] **Step 4: 编译并提交**

```bash
git add common/src/main/java/inspection/common/model/SimulationState.java common/src/main/java/inspection/common/model/CarState.java display/src/main/java/inspection/display/StateBroadcaster.java
git commit -m "feat: SimulationState/CarState 添加 globalPaused + owner 字段"
```

---

### Task 16: ReplayServer 批量快照接口

**Files:**
- Modify: `replay/src/main/java/inspection/replay/ReplayMain.java`

- [ ] **Step 1: 新增 BatchHandler 内部类**

```java
static class BatchHandler implements HttpHandler {
    public void handle(HttpExchange ex) throws IOException {
        String query = ex.getRequestURI().getQuery();
        int from = 0, to = 99;
        if (query != null) {
            for (String p : query.split("&")) {
                if (p.startsWith("from=")) from = Integer.parseInt(p.substring(5));
                else if (p.startsWith("to=")) to = Integer.parseInt(p.substring(3));
            }
        }
        java.util.List<String> result = new java.util.ArrayList<>();
        for (int i = from; i <= to && i < 100000; i++) {
            String json = jedis.lindex("replay:snapshots", i);
            if (json == null) break;
            result.add(json);
        }
        send(ex, 200, JSON.toJSONString(result));
    }
}
```

- [ ] **Step 2: 注册 /api/replay/snapshots 路由**

```java
server.createContext("/api/replay/snapshots", new BatchHandler());
```

放在现有的 `createContext` 列表中。

- [ ] **Step 3: 编译并提交**

```bash
git add replay/src/main/java/inspection/replay/ReplayMain.java
git commit -m "feat: ReplayServer 批量快照接口"
```

---

### Task 17: 前端分析员完整面板

**Files:**
- Modify: `display/src/main/resources/web/index.html`

- [ ] **Step 1: 更新 buildPanel('analyst')**

```javascript
if (role === 'analyst') {
    p.innerHTML =
        '<div style="padding:10px;font-size:12px;color:var(--text2);display:flex;gap:12px;margin-bottom:8px">' +
            '<button onclick="setReplayMode(false)" id="btnLiveMode" style="background:var(--accent);color:#fff;border:none;border-radius:6px;padding:4px 12px;cursor:pointer;font-weight:700">● 实时</button>' +
            '<button onclick="setReplayMode(true)" id="btnReplayMode" style="background:var(--bg-input);color:var(--text2);border:1px solid var(--border);border-radius:6px;padding:4px 12px;cursor:pointer">⏪ 回放</button>' +
        '</div>' +
        section('回放',[
            '<div style="font-size:11px;color:var(--text3);margin-bottom:4px">Tick: <span id="replayCurrent">0</span> / <span id="replayRange">0</span></div>',
            '<div style="display:flex;gap:4px;margin-bottom:8px;flex-wrap:wrap">' +
                '<button onclick="replayStep(-10)" style="background:var(--bg-input);color:var(--text);border:1px solid var(--border);border-radius:4px;padding:4px 8px;cursor:pointer;font-size:11px">⏮</button>' +
                '<button onclick="replayStep(-1)" style="background:var(--bg-input);color:var(--text);border:1px solid var(--border);border-radius:4px;padding:4px 8px;cursor:pointer;font-size:11px">⏪</button>' +
                '<button id="replayPlayBtn" onclick="replayToggle()" style="background:var(--bg-input);color:var(--text);border:1px solid var(--border);border-radius:4px;padding:4px 12px;cursor:pointer;font-size:11px;min-width:50px">▶</button>' +
                '<button onclick="replayStep(1)" style="background:var(--bg-input);color:var(--text);border:1px solid var(--border);border-radius:4px;padding:4px 8px;cursor:pointer;font-size:11px">⏩</button>' +
                '<button onclick="replayStep(10)" style="background:var(--bg-input);color:var(--text);border:1px solid var(--border);border-radius:4px;padding:4px 8px;cursor:pointer;font-size:11px">⏭</button>' +
            '</div>',
            '<div style="margin-bottom:8px"><select id="replaySpeed" onchange="replaySpeedChange()" style="background:var(--bg-input);color:var(--text);border:1px solid var(--border);border-radius:4px;padding:3px 8px;font-size:11px"><option value="0.5">0.5x</option><option value="1" selected>1x</option><option value="2">2x</option><option value="4">4x</option></select></div>',
            '<input type="range" id="replaySlider" min="0" max="100" value="0" oninput="replaySeek(this.value)" style="width:100%;margin-bottom:8px">',
            '<div style="font-size:10px;color:var(--text3)" id="replayInfo">未加载快照 — 点击回放模式加载</div>'
        ]) +
        section('探索率',[
            '<canvas id="chartCanvas" width="280" height="160" style="width:100%;border-radius:6px"></canvas>'
        ]) +
        section('统计',[
            '<div style="font-size:11px"><div style="display:flex;justify-content:space-between;margin-bottom:4px"><span style="color:var(--text3)">总步数</span><span style="color:var(--text);font-weight:600" id="statSteps">0</span></div><div style="display:flex;justify-content:space-between;margin-bottom:4px"><span style="color:var(--text3)">最终探索率</span><span style="color:var(--green);font-weight:600" id="statExplored">0%</span></div></div>',
            '<table class="car-table" style="margin-top:8px"><thead><tr><th>小车</th><th>步数</th></tr></thead><tbody id="perCarStats"><tr><td colspan="2" style="color:var(--text3)">—</td></tr></tbody></table>'
        ]) +
        section('运行状态',[
            '<div style="display:flex;justify-content:space-between;margin-bottom:4px"><span style="font-size:11px;color:var(--text3);font-weight:600">探索率</span><span id="taskStatus" class="task-inactive">未激活</span></div>',
            '<div style="display:flex;align-items:center;gap:12px;margin-bottom:10px"><div class="explore-bar-outer"><div class="explore-bar-inner" id="exploreBar" style="width:0%"></div></div><span class="explore-value" id="exploreRate">0.0%</span></div>',
            '<div class="completed-badge" id="completedBadge">🎉 巡检完成！</div>'
        ]) +
        section('图例',[legendHtml()]);
}
```

- [ ] **Step 2: 添加回放相关 JS 变量和函数**

在文件末尾 `</script>` 之前添加：

```javascript
// ===== 回放 =====
var replayMode = false;
var replaySnapshots = [], replayIdx = 0, replayPlaying = false, replayTimer = null, replaySpeed = 1;
var replayExploredRates = [];

function setReplayMode(on) {
    replayMode = on;
    document.getElementById('btnLiveMode').style.background = on ? 'var(--bg-input)' : 'var(--accent)';
    document.getElementById('btnLiveMode').style.color = on ? 'var(--text2)' : '#fff';
    document.getElementById('btnReplayMode').style.background = on ? 'var(--accent)' : 'var(--bg-input)';
    document.getElementById('btnReplayMode').style.color = on ? '#fff' : 'var(--text2)';
    if (on) {
        replayLoad();
        document.getElementById('replayInfo').style.display = '';
        document.getElementById('replaySlider').style.display = '';
    } else {
        if (replayPlaying) replayToggle();
        document.getElementById('replayInfo').style.display = 'none';
        document.getElementById('replaySlider').style.display = 'none';
        if (currentState) render(currentState);
    }
}

async function replayLoad() {
    try {
        var r = await fetch('http://localhost:8893/api/replay/list');
        var indices = await r.json();
        if (indices.length === 0) {
            document.getElementById('replayInfo').textContent = '无快照数据';
            return;
        }
        var br = await fetch('http://localhost:8893/api/replay/snapshots?from=0&to=' + (indices.length - 1));
        replaySnapshots = await br.json();
        replayMaxTick = replaySnapshots.length;
        document.getElementById('replayRange').textContent = replayMaxTick - 1;
        document.getElementById('replaySlider').max = replayMaxTick - 1;
        replayExploredRates = replaySnapshots.map(function(s){return s.exploredRate||0});
        drawExploredChart();
        document.getElementById('replayInfo').textContent = '共 ' + replayMaxTick + ' 帧快照';
    } catch(e) { document.getElementById('replayInfo').textContent = '回放服务未启动 (localhost:8893)'; }
}

function replayToggle() {
    var btn = document.getElementById('replayPlayBtn');
    if (replayPlaying) {
        clearInterval(replayTimer); replayPlaying = false; btn.textContent = '▶'; return;
    }
    if (replaySnapshots.length === 0) { replayLoad(); return; }
    replayPlaying = true; btn.textContent = '⏸';
    replayTimer = setInterval(function(){
        if (replayIdx >= replaySnapshots.length - 1) { clearInterval(replayTimer); replayPlaying = false; btn.textContent = '▶'; return; }
        replayIdx++; replayRender(replayIdx);
    }, 500 / replaySpeed);
}

function replayStep(delta) {
    if (replaySnapshots.length === 0) return;
    replayIdx = Math.max(0, Math.min(replaySnapshots.length - 1, replayIdx + delta));
    replayRender(replayIdx);
}

function replaySeek(val) {
    replayIdx = parseInt(val);
    if (replaySnapshots.length > 0) replayRender(replayIdx);
}

function replayRender(idx) {
    document.getElementById('replayCurrent').textContent = idx;
    document.getElementById('replaySlider').value = idx;
    // 将 snapshot JSON 转为兼容 render() 的格式
    var snap = replaySnapshots[idx];
    if (snap.mapBits) {
        snap.mapView = [];
        for (var y = 0; y < (snap.mapHeight||40); y++) {
            var row = [];
            for (var x = 0; x < (snap.mapWidth||40); x++) {
                row.push(snap.mapBits[y*snap.mapWidth + x] === '1');
            }
            snap.mapView.push(row);
        }
    }
    render(snap);
}

function replaySpeedChange() {
    replaySpeed = parseFloat(document.getElementById('replaySpeed').value);
    if (replayPlaying) { replayToggle(); replayToggle(); }
}

function updateLiveStats(state) {
    if (!state) return;
    var ts = (state.cars||[]).reduce(function(s,c){return s+(c.steps||0)},0);
    var el = document.getElementById('statSteps'); if (el) el.textContent = ts;
    el = document.getElementById('statExplored'); if (el) el.textContent = ((state.exploredRate||0)*100).toFixed(1)+'%';
    var tbody = document.getElementById('perCarStats');
    if (tbody && state.cars) {
        var rows = '';
        state.cars.forEach(function(c){
            rows += '<tr><td>'+c.carId+'</td><td class="car-steps">'+(c.steps||0)+'</td></tr>';
        });
        tbody.innerHTML = rows || '<tr><td colspan="2" style="color:var(--text3)">—</td></tr>';
    }
}
```

- [ ] **Step 3: 修改 ws.onmessage — 实时模式渲染 + 始终更新统计**

```javascript
ws.onmessage = function(event){
    try {
        var msg = JSON.parse(event.data);
        if (msg.type === 'AUTH_OK') { MACHINE_ID = msg.machine; return; }
        if (!replayMode) {
            currentState = msg;
            render(msg);
        }
        updateLiveStats(msg);
    } catch(e) { console.error(e); }
};
```

- [ ] **Step 4: 实现 drawExploredChart()**

```javascript
function drawExploredChart() {
    var canvas = document.getElementById('chartCanvas');
    if (!canvas) return;
    var ctx = canvas.getContext('2d');
    var W = canvas.width, H = canvas.height;
    var data = replayExploredRates;
    ctx.fillStyle = '#0d1524'; ctx.fillRect(0, 0, W, H);
    if (!data || data.length === 0) return;
    var pad = {left: 36, right: 8, top: 8, bottom: 22};
    var pw = W - pad.left - pad.right, ph = H - pad.top - pad.bottom;

    ctx.strokeStyle = '#1a2744'; ctx.lineWidth = 0.5;
    for (var pct = 0; pct <= 100; pct += 25) {
        var y = pad.top + ph * (1 - pct / 100);
        ctx.beginPath(); ctx.moveTo(pad.left, y); ctx.lineTo(W - pad.right, y); ctx.stroke();
        ctx.fillStyle = '#55657e'; ctx.font = '8px sans-serif'; ctx.textAlign = 'right';
        ctx.fillText(pct + '%', pad.left - 3, y + 3);
    }

    ctx.beginPath(); ctx.strokeStyle = '#34d399'; ctx.lineWidth = 1.2;
    for (var i = 0; i < data.length; i++) {
        var x = pad.left + pw * (i / (data.length - 1));
        var y = pad.top + ph * (1 - data[i]);
        if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
    }
    ctx.stroke();
}
```

- [ ] **Step 5: 编译并提交**

```bash
git add display/src/main/resources/web/index.html
git commit -m "feat: 分析员完整面板 — 回放控件 + 折线图 + 统计"
```

---

### Task 18: 剩余项收尾

**Files:**
- Modify: `auth/src/main/java/inspection/auth/PasswordHasher.java`
- Modify: `common/src/main/java/inspection/common/Launcher.java`
- Modify: `多人协作与部署方案.md`

- [ ] **Step 1: PasswordHasher 注释更新**

在 `hash()` 方法注释中更新：

```java
/**
 * 两层 SHA-256 存储哈希
 * @param password 前端 SHA-256 后传输的 transHash（非明文密码）
 * @return "salt:hash" 格式字符串，其中 hash = SHA-256(salt + transHash)
 */
```

- [ ] **Step 2: Launcher — Display 启动加 --machine 主**

在 `launchWithArgs("Display", ...)` 调用中，给 extraArgs 加上 `"--machine", "主"`：

```java
launchWithArgs("Display", "display",
        "inspection.display.DisplayMain", commonClasses, 3000,
        "--http-port", String.valueOf(displayHttpPort),
        "--car-count", String.valueOf(carCount),
        "--machine", "主");
```

- [ ] **Step 3: 架构文档更新**

在 `多人协作与部署方案.md` 末尾追加实施记录：

```markdown
## 附录：实施记录（2026-06-20）

以下功能已实施：

| 功能 | 状态 |
|------|------|
| 前端 SHA-256 密码传输 | ✅ |
| 后端两层 SHA-256(salt+transHash) | ✅ |
| WebSocket Token 认证 (AUTH/AUTH_OK) | ✅ |
| 后端权限校验 (role-based) | ✅ |
| 配置员 IP 限制 (仅 localhost) | ✅ |
| 配置员完整面板（全局控制/用户管理/障碍物编辑） | ✅ |
| 运行员面板（我的小车/personal 暂停/全局暂停锁定） | ✅ |
| 分析员面板（回放控件/折线图/统计） | ✅ |
| 全局暂停 + 运行员个人暂停 | ✅ |
| Car owner 归属 (machineId) | ✅ |
| 所有模块支持 --redis-host/port --mq-host/port | ✅ |
| Display --machine + /api/config 端点 | ✅ |
| ReplayServer 批量快照接口 + 端口 8893 | ✅ |
| Launcher 单机调试模式 | ✅ |

各机器启动命令参见第四章。
```

- [ ] **Step 4: 编译并提交**

```bash
git add auth/src/main/java/inspection/auth/PasswordHasher.java common/src/main/java/inspection/common/Launcher.java "多人协作与部署方案.md"
git commit -m "chore: PasswordHasher 注释更新 + Launcher --machine 主 + 架构文档记录"
```

---

### Task 19: 全量编译验证

- [ ] **Step 1: 完整编译**

```bash
cd C:\workplace\ruanti\BlackBoxAI && mvn compile -q
```
Expected: BUILD SUCCESS，无编译错误

- [ ] **Step 2: 检查所有模块 class 文件已生成**

```bash
ls common/target/classes/inspection/common/config/ArgsParser.class
ls auth/target/classes/inspection/auth/AuthServerMain.class
ls display/target/classes/inspection/display/CommandReceiver.class
ls controller/target/classes/inspection/controller/ControllerAgent.class
ls replay/target/classes/inspection/replay/ReplayMain.class
```
Expected: 所有文件存在

- [ ] **Step 3: 提交 final 验证**

```bash
git add -A && git status
git commit -m "verify: 全量编译通过"
```
