
# 基于黑板架构的多机器人协作巡检仿真系统
# 五人团队 — IDEA 开发分工与集成方案

> **开发环境**：IntelliJ IDEA + JDK 17 + Maven 3.8+  
> **基础设施**：Redis 5.x + RabbitMQ 3.x  

---

## 一、项目初始化（A 负责，Day 1 上午完成）

### 1.1 创建 IDEA Maven 多模块项目

A 在 IDEA 中执行（其他人等待 A 推送代码后 clone）：

```
File → New → Project → Maven Archetype
  GroupId:    com.inspection
  ArtifactId: BlackBoxAI
  JDK:        17
```

创建后在项目根目录 **删除 src 目录**（父 POM 不需要代码），然后逐个添加子模块：

```
右键 BlackBoxAI → New → Module → Maven Archetype (quickstart)
  依次创建:
    common              (A)
    controller          (B)
    car                 (C)
    navigator           (D)
    target-planner      (E)
    task-configurator   (E)
    display             (E)
```

### 1.2 最终项目结构

```
BlackBoxAI/                              ← IDEA 打开这个目录
├── pom.xml                              ← 父 POM（A 维护）
├── common/                              ← 模块 A
│   ├── pom.xml
│   └── src/main/java/inspection/common/
│       ├── model/
│       │   ├── Point.java
│       │   ├── CarState.java
│       │   ├── SimulationState.java
│       │   └── MQMessage.java
│       ├── enums/
│       │   ├── CarStatus.java
│       │   └── CommandType.java
│       ├── client/
│       │   ├── BlackboardClient.java    ← Redis 黑板读写
│       │   ├── MessageBusClient.java    ← RabbitMQ 收发
│       │   └── DistributedLock.java
│       └── config/
│           └── ConfigConstants.java
│
├── controller/                          ← 模块 B
│   ├── pom.xml
│   └── src/main/java/inspection/controller/
│       ├── ControllerMain.java
│       ├── TickLoop.java
│       ├── CommandHandler.java
│       └── ExplorationCalculator.java
│
├── car/                                 ← 模块 C
│   ├── pom.xml
│   └── src/main/java/inspection/car/
│       ├── CarMain.java
│       ├── CarAgent.java
│       ├── Illuminator.java
│       └── DynamicObstacleManager.java
│
├── navigator/                           ← 模块 D
│   ├── pom.xml
│   └── src/main/java/inspection/navigator/
│       ├── NavigatorMain.java
│       ├── PathPlanner.java
│       ├── BFSPlanner.java
│       └── AStarPlanner.java
│
├── target-planner/                      ← 模块 E-1
│   ├── pom.xml
│   └── src/main/java/inspection/targetplanner/
│       └── TargetPlannerMain.java
│
├── task-configurator/                   ← 模块 E-2
│   ├── pom.xml
│   └── src/main/java/inspection/taskconfigurator/
│       └── TaskConfiguratorMain.java
│
└── display/                             ← 模块 E-3
    ├── pom.xml
    └── src/main/java/inspection/display/
        ├── DisplayMain.java
        ├── StateBroadcaster.java
        └── CommandReceiver.java
    └── src/main/resources/web/          ← 前端页面放这里
        └── index.html
```

### 1.3 父 POM 内容（A 创建并提交）

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.inspection</groupId>
    <artifactId>BlackBoxAI</artifactId>
    <version>1.0-SNAPSHOT</version>
    <packaging>pom</packaging>

    <modules>
        <module>common</module>
        <module>controller</module>
        <module>car</module>
        <module>navigator</module>
        <module>target-planner</module>
        <module>task-configurator</module>
        <module>display</module>
    </modules>

    <properties>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <dependencies>
        <!-- 所有模块共同的依赖 -->
        <dependency>
            <groupId>com.alibaba.fastjson2</groupId>
            <artifactId>fastjson2</artifactId>
            <version>2.0.53</version>
        </dependency>
        <dependency>
            <groupId>redis.clients</groupId>
            <artifactId>jedis</artifactId>
            <version>5.2.0</version>
        </dependency>
        <dependency>
            <groupId>com.rabbitmq</groupId>
            <artifactId>amqp-client</artifactId>
            <version>5.22.0</version>
        </dependency>
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-simple</artifactId>
            <version>2.0.16</version>
        </dependency>
    </dependencies>
</project>
```

### 1.4 各子模块 POM 模板

```xml
<!-- 以 car 模块为例，其他模块同理 -->
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <parent>
        <groupId>com.inspection</groupId>
        <artifactId>BlackBoxAI</artifactId>
        <version>1.0-SNAPSHOT</version>
    </parent>

    <artifactId>car</artifactId>

    <dependencies>
        <!-- 核心：依赖 common 模块 -->
        <dependency>
            <groupId>com.inspection</groupId>
            <artifactId>common</artifactId>
            <version>1.0-SNAPSHOT</version>
        </dependency>
    </dependencies>
</project>
```

> ⚠️ A 在 `common/pom.xml` 里**不要**写 `<parent>` 的依赖（它不需要依赖自己），只写自己额外的依赖（如果有的话）。

---

## 二、Git 协作规范（全体遵守）

### 2.1 仓库初始化

```bash
# A 执行
cd BlackBoxAI
git init
git add .
git commit -m "init: Maven多模块项目骨架 + common接口定义"
git remote add origin <你们仓库地址>
git push -u origin main
```

```bash
# B/C/D/E 执行（从 IDEA 操作或命令行均可）
git clone <仓库地址>
# 然后用 IDEA 打开 BlackBoxAI 目录
```

### 2.2 分支策略——每人一个分支，只改自己模块

```
main ──────────────────────────────────────────────
  │
  ├── dev-A  (A 的分支，只改 common/)
  ├── dev-B  (B 的分支，只改 controller/)
  ├── dev-C  (C 的分支，只改 car/)
  ├── dev-D  (D 的分支，只改 navigator/)
  └── dev-E  (E 的分支，只改 target-planner/ + task-configurator/ + display/)
```

### 2.3 IDEA 中的 Git 操作流程

**每人每天的标准流程**：

```
1. IDEA 右下角点击分支名 → 切换到自己的 dev-X 分支

2. 拉取最新代码（防止冲突）:
   Git → Pull → 选 main → Pull
   # 相当于 git pull origin main（把别人的更新合并到自己分支）

3. 写代码，只改自己模块目录下的文件

4. 提交:
   Ctrl+K → 勾选自己改的文件 → 写 commit message → Commit

5. 推送:
   Ctrl+Shift+K → Push

6. 发起 Merge Request（在 Git 平台操作，或 IDEA 里 VCS → Git → Create Pull Request）
```

### 2.4 Commit Message 规范

```
[模块缩写] 简短描述

示例:
[common] 完成 BlackboardClient 全部方法
[car] 实现 handleTickMove 完整流程
[nav] 修复 BFS 越界 bug
[ctrl] Controller 单实例锁改为乐观锁
```

### 2.5 IDEA 中防止改错文件

IDEA 有个很实用的功能：

```
Settings → Version Control → Confirmation
  → 勾选 "Show options before adding to VCS"
  → 勾选 "Show options before removing from VCS"
```

提交前 `Ctrl+K` 时，**只看自己模块目录**的文件，别人的文件一律不勾选。

如果发现自己误改了他人的文件：
```
右键文件 → Git → Rollback  （恢复原始版本）
```

---

## 三、分工详情（IDEA 开发视角）

### 成员 A — common 公共模块

**包路径**：`inspection.common.*`

**你在 IDEA 里只动 `common/` 目录。**

#### 交付物

| 文件 | 包 | 说明 |
|------|-----|------|
| `Point.java` | `model` | 坐标 (x, y)，含 `equals/hashCode/distanceTo` |
| `CarState.java` | `model` | 单台小车状态快照 |
| `SimulationState.java` | `model` | 完整仿真状态（给前端用） |
| `MQMessage.java` | `model` | 消息模型 `{cmd, data, timestamp}` |
| `CarStatus.java` | `enums` | IDLE / WAITING_ROUTE / READY / MOVING / BLOCKED |
| `CommandType.java` | `enums` | 全部命令枚举 |
| `BlackboardClient.java` | `client` | **核心**：封装全部 Redis Key 读写 |
| `MessageBusClient.java` | `client` | **核心**：封装全部 RabbitMQ 收发 |
| `DistributedLock.java` | `client` | 分布式锁封装 |
| `ConfigConstants.java` | `config` | 全局常量 |

#### BlackboardClient 需要封装的方法（其他人只通过你写的方法访问 Redis）

```java
// ===== 地图 =====
boolean[][] getMapView();
void setMapViewBit(int x, int y);
boolean isExplored(int x, int y);
void illuminateArea(int cx, int cy);  // 3×3 点亮
boolean isBlocked(int x, int y);
void setBlocked(int x, int y);
void clearBlocked(int x, int y);

// ===== 小车状态 =====
CarStatus getCarStatus(String carId);
void setCarStatus(String carId, CarStatus status);  // ⚠️ 这方法只有 Controller 和 Car 调用
Point getCarPosition(String carId);
void setCarPosition(String carId, int x, int y);
int getCarSteps(String carId);
void incrementCarSteps(String carId);

// ===== 小车任务 =====
Point getCarTarget(String carId);
void setCarTarget(String carId, int x, int y);
void clearCarTarget(String carId);

// ===== 路径 =====
void pushRoute(String carId, List<Point> path);  // Navigator 调用，LPUSH
Point popNextStep(String carId);                  // Car 调用，RPOP
Point peekNextStep(String carId);                 // Car 调用，查看不消费
void clearRoute(String carId);                    // Controller/Car 调用

// ===== 受阻 =====
void setBlockedTick(String carId, long tick);
long getBlockedTick(String carId);

// ===== 全局配置 =====
Map<String, String> getTaskConfig();
void setTaskConfig(Map<String, String> config);
boolean isTaskActive();
void setTaskActive(boolean active);

// ===== 锁 =====
DistributedLock getCarLock(String carId);
DistributedLock getControllerLock();

// ===== 管理 =====
void clearAll();  // 仅 TaskConfigurator 调用
```

#### 你写完后的自测方式

在 `common/src/main/java/inspection/common/` 下新建 `TestMain.java`（不需要测试框架，就是 main）：

```java
public class TestMain {
    public static void main(String[] args) {
        BlackboardClient bb = new BlackboardClient("localhost", 6379);
        // 1. 测试写位置
        bb.setCarPosition("Car001", 5, 3);
        // 2. 测试读位置
        Point p = bb.getCarPosition("Car001");
        System.out.println("位置: (" + p.getX() + "," + p.getY() + ")");
        // 预期: 位置: (5,3)
        // 3. redis-cli 验证
        // > HGETALL Car001:Position
        // 预期: x=5, y=3
    }
}
```

IDEA 里右键 `TestMain` → Run，观察控制台 + redis-cli 验证。

---

### 成员 B — controller 调度控制器

**包路径**：`inspection.controller.*`

**你在 IDEA 里只动 `controller/` 目录。**

#### 核心逻辑

```
每个节拍（tickInterval=500ms）:

Step 1: if (!isTaskActive()) return;   // 任务未激活，跳过

Step 2: 计算探索率
  explored = mapView 中 1 的个数
  total = mapWidth × mapHeight − obstacleCount
  if (explored / total ≥ 99.9%) → 巡检完成，停止节拍

Step 3: 处理车辆 Car001
  switch (getCarStatus("Car001")):
    IDLE          → clearCarTarget + clearRoute → send(ASSIGN_TARGET) to TargetPlannerCmd
    WAITING_ROUTE → send(PLAN_ROUTE) to NavigatorCmd
    READY         → 跳过
    MOVING        → setCarStatus(READY)   // 异常恢复
    BLOCKED       → if (tick − getBlockedTick ≥ 2) → clearRoute + clearTarget + setCarStatus(IDLE)

Step 4: 若 Car001 为 READY → send(TICK_MOVE) to Car_Car001

Step 5: broadcast(REFRESH_ALL) to UpdateView Fanout

Step 6: 处理 Web 命令缓存 → SET_CONFIG/RESET → send to TaskConfigCmd
```

#### 消息处理（订阅 ControllerCmd 队列）

收到知识源的回复消息：

| 消息 | 动作 |
|------|------|
| `TASK_READY` | `setTaskActive(true)` |
| `TARGET_ASSIGNED` | 遍历 assignedCars → `setCarTarget` → `setCarStatus(WAITING_ROUTE)` |
| `ROUTE_PLANNED` | `routeFound ? setCarStatus(READY) : setCarStatus(IDLE)` |
| `MOVED` | 记录日志（无额外操作） |
| `BLOCKED` | `setBlockedTick` → 等超时自动处理 |
| `ROUTE_DONE` | `clearRoute` + `clearTarget` + `setCarStatus(IDLE)` |

#### 单实例锁

```java
// ControllerMain 启动时
public static void main(String[] args) {
    BlackboardClient bb = new BlackboardClient("localhost", 6379);
    if (!bb.getControllerLock().tryLock()) {
        System.err.println("❌ 系统中已有 Controller 实例运行！");
        System.exit(1);
    }
    // 启动定时续期 + 节拍循环
    new TickLoop(bb, mq).start();
}
```

#### IDEA Run Configuration（你调试用）

```
Run → Edit Configurations → + → Application

Name:         B-Controller
Module:       controller
Main class:   inspection.controller.ControllerMain
```

#### 你写完后的自测方式

```bash
# 1. 确保 Redis + RabbitMQ 运行
# 2. 手动用 redis-cli 设 taskActive:
redis-cli HSET TaskConfig taskActive 1
redis-cli HSET TaskConfig mapWidth 30
redis-cli HSET TaskConfig mapHeight 30
redis-cli HSET TaskConfig carCount 1
# 3. IDEA 里点 Run B-Controller
# 4. 观察控制台日志: "节拍#1 → taskActive=true → IDLE 车发 ASSIGN_TARGET ..."
# 5. 看 redis-cli 中 Car001:Status 是否有变化
```

---

### 成员 C — car 小车知识源

**包路径**：`inspection.car.*`

**你在 IDEA 里只动 `car/` 目录。**

#### 核心：handleTickMove 完整流程

```
收到 TICK_MOVE 消息:

1. if (getCarStatus(carId) ≠ READY) → 忽略，直接 return

2. 🔒 bb.getCarLock(carId).tryLock()

3. Point next = bb.peekNextStep(carId)
   if (next == null) → handleRouteDone() → 🔓 return

4. if (bb.isBlocked(next.x, next.y)) → handleBlocked(currentTick) → 🔓 return

5. bb.setCarStatus(carId, MOVING)

6. bb.popNextStep(carId)              // 消费这一步

7. 清除旧位置动态障碍（bb.clearBlocked 旧位置）

8. bb.setCarPosition(carId, next.x, next.y)

9. 设置新位置动态障碍（bb.setBlocked 新位置）

10. bb.illuminateArea(next.x, next.y)  // 点亮 3×3

11. bb.incrementCarSteps(carId)

12. Point stillNext = bb.peekNextStep(carId)
    if (stillNext != null):
        bb.setCarStatus(carId, READY)
        发 MOVED 到 ControllerCmd
    else:
        handleRouteDone()

13. 🔓 unlock

handleBlocked(tick):
  bb.setCarStatus(carId, BLOCKED)
  bb.setBlockedTick(carId, tick)
  发 BLOCKED 到 ControllerCmd
  🔓

handleRouteDone():
  bb.setCarStatus(carId, IDLE)
  bb.clearCarTarget(carId)
  bb.clearRoute(carId)
  发 ROUTE_DONE 到 ControllerCmd
  🔓
```

#### IDEA Run Configuration（你调试用）

你需要给 Car001 创建配置：

```
Run → Edit Configurations → + → Application

模板:
  Module:       car
  Main class:   inspection.car.CarMain
  Program args: Car001

创建:
  CR-Car001 → args: Car001
```

#### 初始化位置

小车初始位置（TaskConfigurator 会设置，你知道就行）：

| CarID | 位置 | 描述 |
|-------|------|------|
| Car001 | (15, 15) | 地图中心 |

#### 你写完后的自测方式

```bash
# 1. 用 redis-cli 手动搭测试环境：
redis-cli SET Car001:Status READY
redis-cli HSET Car001:Position x 5 y 3
redis-cli LPUSH Car001:RouteList '{"x":6,"y":3}'   # 压入 2 步路径
redis-cli LPUSH Car001:RouteList '{"x":7,"y":3}'

# 2. IDEA 点 Run CR-Car001

# 3. 手动向 MQ 队列 Car_Car001 发 TICK_MOVE（或用 RabbitMQ Management 面板发）
#    消息内容: {"cmd":"TICK_MOVE","data":{},"timestamp":123}

# 4. 观察控制台日志: [Car:Car001] 收到 TICK_MOVE → READY → 移动至(6,3) → Status=READY

# 5. redis-cli 验证：
redis-cli GET Car001:Status         # READY
redis-cli HGETALL Car001:Position   # x=6, y=3
redis-cli GETBIT mapView 183        # 3×30+3=93附近，应变为 1（点亮了）
```

---

### 成员 D — navigator 导航器

**包路径**：`inspection.navigator.*`

**你在 IDEA 里只动 `navigator/` 目录。**

#### 核心算法：BFS

```java
public List<Point> bfs(Point start, Point target, boolean[][] mapBlock, int w, int h) {
    Queue<Point> queue = new LinkedList<>();
    boolean[][] visited = new boolean[h][w];
    Map<Point, Point> parent = new HashMap<>();

    queue.add(start);
    visited[start.y][start.x] = true;

    int[][] dirs = {{0,1},{0,-1},{1,0},{-1,0}};  // 上下左右

    while (!queue.isEmpty()) {
        Point cur = queue.poll();
        if (cur.equals(target)) {
            return reconstructPath(parent, target);  // 回溯路径
        }
        for (int[] d : dirs) {
            int nx = cur.x + d[0], ny = cur.y + d[1];
            if (nx >= 0 && nx < w && ny >= 0 && ny < h
                && !visited[ny][nx] && !mapBlock[ny][nx]) {
                visited[ny][nx] = true;
                Point next = new Point(nx, ny);
                parent.put(next, cur);
                queue.add(next);
            }
        }
    }
    return null;  // 无路径
}
```

#### A* 算法（当 algorithm=A_STAR 时使用）

```java
// 估价函数: 曼哈顿距离
int heuristic(Point a, Point b) {
    return Math.abs(a.x - b.x) + Math.abs(a.y - b.y);
}
// 核心: 优先队列按 f = g + h 排序
// g = 从起点到当前的实际步数
// h = 曼哈顿距离到目标
```

#### 处理 PLAN_ROUTE 的完整流程

```
收到 {"cmd":"PLAN_ROUTE", "data":{"carId":"Car001", "algorithm":"BFS"}}:

1. Point pos = bb.getCarPosition(carId)
2. Point target = bb.getCarTarget(carId)
3. if (bb.isBlocked(target.x, target.y)):
     发 ROUTE_PLANNED({"routeFound": false}) → return

4. List<Point> path = algorithm.equals("A_STAR")
      ? astar(pos, target, mapBlock, w, h)
      : bfs(pos, target, mapBlock, w, h)

5. if (path == null):
     发 ROUTE_PLANNED({"routeFound": false})
   else:
     🔒 bb.getCarLock(carId).tryLock()
     bb.pushRoute(carId, path)    // LPUSH 整条路径
     🔓 unlock
     发 ROUTE_PLANNED({"routeFound": true, "routeLength": path.size()})
```

#### 多实例——你一个人的事

你写完一个 Navigator，多实例是这样启动的：

```
IDEA Run Configurations:
  D-Navigator-1    → Module: navigator, Main: NavigatorMain
  D-Navigator-2    → Module: navigator, Main: NavigatorMain  （复制一份，完全一样）
  D-Navigator-3    → Module: navigator, Main: NavigatorMain  （复制一份）

三个都启动，绑定同一个 NavigatorCmd 队列
RabbitMQ 自动轮询分发任务，你不需要写任何负载均衡代码
```

#### 你写完后的自测方式

```bash
# 1. redis-cli 搭环境：
redis-cli HSET Car001:Position x 1 y 1
redis-cli SET Car001:Target '{"x":10,"y":10}'

# 2. IDEA 点 Run D-Navigator

# 3. 向 NavigatorCmd 队列手动发消息（RabbitMQ Management → Queues → NavigatorCmd → Publish message）：
{"cmd":"PLAN_ROUTE","data":{"carId":"Car001","algorithm":"BFS"},"timestamp":123}

# 4. redis-cli 验证：
redis-cli LRANGE Car001:RouteList 0 -1
# 应看到从 (1,1) 到 (10,10) 的完整路径序列
```

---

### 成员 E — 三合一（TargetPlanner + TaskConfigurator + Display）

**你在 IDEA 里只动三个目录**：`target-planner/`、`task-configurator/`、`display/`

#### E-1: TargetPlanner（目标规划器）

```
收到 {"cmd":"ASSIGN_TARGET", "data":{"carId":"Car001"}}:

1. 扫描未探索区域:
   unexplored = []
   for y in 0..29:
     for x in 0..29:
       if (!bb.isExplored(x,y) && !bb.isBlocked(x,y)):
         unexplored.add((x,y))

2. 贪心分配:
   carPos = bb.getCarPosition(carId)
   候选 = unexplored.filter(p → 
     // 排除已在其他车 Target 中的点
     // 距离 ≥ 10 规则（剩余 > 1 时）
   )
   best = 候选中距 carPos 最近的点
   bb.setCarTarget(carId, best.x, best.y)

3. 发 TARGET_ASSIGNED({"assignedCars":[{"carId":"Car001","targetX":20,"targetY":15}]})
```

#### E-2: TaskConfigurator（任务设置器）

```
收到 {"cmd":"FORWARD_CONFIG", "data":{"mapWidth":30,"mapHeight":30,...}}:

1. bb.clearAll()   // FLUSHDB

2. 随机生成障碍物:
   - 数量 = mapWidth × mapHeight × obstacleDensity ≈ 90
   - 避开 Car001 初始位置 (15,15) 及周围 3×3

3. 写入 Redis:
   - 每个障碍: bb.setBlocked(x, y)
   - Car001: bb.setCarPosition + bb.setCarStatus(IDLE) + bb.setSteps(0)
   - TaskConfig HSET 全部字段

4. 声明 MQ 队列（Car_Car001, NavigatorCmd, TargetPlannerCmd, TaskConfigCmd, ControllerCmd）
   声明 UpdateView Fanout Exchange

5. 发 TASK_READY
```

#### E-3: Display（WebSocketBridge + 前端）

**服务端**：
```
收到 REFRESH_ALL:
1. 全量读黑板 → 构建 SimulationState JSON
2. WebSocket 广播给所有已连浏览器

收到浏览器 SET_CONFIG → 转发 ControllerCmd
收到浏览器 RESET → 转发 ControllerCmd
```

**前端页面**（`display/src/main/resources/web/index.html`）：
- Canvas 600×600 渲染 30×30 网格
- 颜色：未探索=#333, 已探索=#C8E6C9, 障碍=#5D4037, 小车按状态色
- 控制面板：启动/停止/重置 + 参数配置 + 探索率显示

#### IDEA Run Configurations

```
E-TargetPlanner       → Module: target-planner,  Main: TargetPlannerMain
E-TaskConfigurator    → Module: task-configurator, Main: TaskConfiguratorMain
E-Display             → Module: display,          Main: DisplayMain, Args: --port=8887
```

---

## 四、IDEA 联调：Compound Run Configuration

### 4.1 创建所有独立配置

确保下面每个都能单独 Run 成功：

| 序号 | 配置名 | 模块 | Main Class | Args | 负责人 |
|:----:|--------|------|-----------|------|:------:|
| 1 | TC-TaskConfigurator | task-configurator | `TaskConfiguratorMain` | — | E |
| 2 | TP-TargetPlanner | target-planner | `TargetPlannerMain` | — | E |
| 3 | NV-Navigator | navigator | `NavigatorMain` | — | D |
| 4 | CR-Car001 | car | `CarMain` | `Car001` | C |
| 5 | CT-Controller | controller | `ControllerMain` | — | B |
| 6 | DP-Display | display | `DisplayMain` | `--port=8887` | E |

### 4.2 分组 Compound

```
Run → Edit Configurations → + → Compound

📦 联调-准备阶段（先跑这个）
  勾选:
    TC-TaskConfigurator       ← 第一个，初始化黑板
    TP-TargetPlanner
    NV-Navigator
    CR-Car001
    DP-Display

📦 联调-启动调度（后跑这个）
  勾选:
    CT-Controller             ← 最后启动
```

### 4.3 联调实操 6 步

```
步骤1: 确保 Redis + RabbitMQ 已启动
       redis-cli PING → PONG

步骤2: IDEA → Build → Rebuild Project
       确保全部模块编译通过

步骤3: Run → 📦 联调-准备阶段
       等 TaskConfigurator 控制台输出 "TASK_READY"

步骤4: 打开浏览器 → http://localhost:8887
       确认能看到空白地图（还没启动巡检）

步骤5: Run → 📦 联调-启动调度
       Controller 控制台输出 "单实例锁获取成功" + "节拍循环启动"

步骤6: 浏览器点「启动巡检」
       观察 Canvas 上小车开始移动、地图逐步点亮
       探索率达 100% → Controller 输出 "巡检完成"
```

---

## 五、IDEA 调试指南

### 5.1 Debug 单个模块

```
IDEA 右上角选对应配置 → 点 🐞 Debug（不要点 ▶ Run）

举例：调试 Car001 为什么不移动
  → 选 CR-Car001 → Debug
  → 在 CarAgent.handleTickMove() 第 1 行打断点
  → 看 Status 是不是 READY，路径是不是空
```

### 5.2 同时 Debug 多个模块

IDEA 支持多进程同时 Debug：

```
1. 先正常 Run 📦 联调-准备阶段
2. 对 CT-Controller 点 🐞 Debug（只 Debug Controller）
3. Controller 断点停住时，其他进程照常运行
4. Debug 面板 → Threads 标签 → 看当前线程栈
```

### 5.3 关键断点位置

| 场景 | 在哪打断点 |
|------|-----------|
| Controller 不调度 | `TickLoop.run()` 的 `if (!isTaskActive()) return` |
| 目标没分配 | `CommandHandler` 收到 TARGET_ASSIGNED 处 |
| 车不移动 | `CarAgent.handleTickMove()` 第 3 行 |
| 路径不对 | `BFSPlanner.bfs()` 找到路径那行 |
| 地图不亮 | `Illuminator.illuminate()` 循环内 |
| 前端收不到 | `StateBroadcaster.broadcast()` JSON 序列化处 |

### 5.4 IDEA 控制台管理

6 个进程同时跑，控制台按 Tab 分开：

```
Run 面板
├── [TC-TaskConfigurator]    ← 初始化日志
├── [CT-Controller]          ← 节拍日志在这里看
├── [CR-Car001]              ← 小车移动日志
├── [NV-Navigator]           ← 路径规划日志
├── [TP-TargetPlanner]       ← 目标分配日志
└── [DP-Display]             ← WebSocket 连接日志
```

**实用技巧**：在控制台搜索框输入 `[Controller]` 只看调度日志；搜索 `ERROR` 过滤错误。

---

## 六、开发日程

```
Day 1 上午（2h）
  A: 创建项目 + 推送仓库
  B/C/D/E: 克隆仓库 + IDEA 导入
  全员: 确认父 POM 依赖下载成功、编译通过

Day 1 下午（3h）
  A: 写 common 模块全部代码 + 自测通过 + 提交
  B/C/D/E: 拉取 A 的代码，验证 common 可用

Day 2 全天（6h）
  全员独立开发各自模块:
    B → controller
    C → car
    D → navigator
    E → target-planner + task-configurator + display

Day 2 晚上
  全员: 各自自测通过 + 提交代码 + MR 合入 main

Day 3 上午（3h）
  全员合代码 → Build → Rebuild → 逐个 Run 验证
  → Run 📦 联调-准备阶段 → Run 📦 联调-启动调度

Day 3 下午（3h）
  联合调试 + Bug 修复 + 验收
```

---

## 七、快速检查清单（联调前各自确认）

| 成员 | 自检项 | 命令/操作 |
|:----:|--------|----------|
| A | common 编译通过 | `mvn clean compile -pl common` |
| A | BlackboardClient 全方法可调 | Run TestMain |
| B | Controller 单实例锁有效 | 同时 Run 两个 CT-Controller，第二个应报错退出 |
| B | 节拍日志正常输出 | Run CT-Controller，手动设 taskActive=1 |
| C | Car001 收到 TICK_MOVE 后移动 | 手动搭 Redis 测试环境验证 |
| C | 3×3 点亮正确 | redis-cli GETBIT 验证 9 个格子 |
| D | BFS 找到路径 | 设起点(1,1)终点(10,10)，验证 RouteList |
| D | A* 路径不差于 BFS | 同一场景对比步数 |
| E | TaskConfigurator 初始化后 Redis 数据完整 | `redis-cli KEYS *` 确认所有 Key |
| E | TargetPlanner 距离 ≥ 10 规则正确 | 部分探索场景验证 |
| E | 浏览器能打开并显示地图 | `http://localhost:8887` |
| 全员 | 各自的 Run Config 能单独启动，不报错 | IDEA 逐个 Run |
