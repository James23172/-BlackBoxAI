# 分布式多车协作地图探索仿真系统 — 架构文档

## 一、项目概述

### 1.1 项目名称

分布式多车协作地图探索仿真系统

### 1.2 项目定位

面向变电站巡检、城市道路测绘场景的多机器人协作探索仿真程序，通过软件模拟多台无人车在未知环境中的分布式自主探索、地图构建与协作行为，验证多机覆盖探索算法的有效性。

### 1.3 核心目标

实现松耦合消息驱动的多车协作，在无人工干预的前提下，自主完成未知环境的全区域覆盖探索，输出完整的环境地图与探索过程可视化，验证多机器人协作探索的效率优势。

### 1.4 需求背景

- **变电站智能巡检**：多台巡检机器人自主探索厂区环境，覆盖所有设备区域，构建厂区地图并定位关键设备点位
- **城市道路测绘**：多台测绘车协作探索未知路段，采集道路信息，快速构建高精度道路地图
- **应急救援场景**：多台无人车进入未知灾害区域，协作探索环境、搜索目标，避免单机器人故障导致任务失败

期末课程设计任务，要求实现分布式多智能体协作算法，完成路径规划与环境探索功能，具备可视化演示能力。

---

## 二、架构风格

采用 **黑板风格 + 消息驱动** 的混合架构：

```
                    ┌──────────────────────────┐
                    │      黑板 (Redis)          │
                    │  ┌──────────────────────┐ │
                    │  │ taskQueue (FIFO List) │ │
                    │  │ map:view / blocked   │ │
                    │  │ car:{id}:route ...   │ │
                    │  └──────────────────────┘ │
                    └──────────▲────────────────┘
                               │ 读 FIFO 队列
                    ┌──────────┴───────────┐
                    │    Controller          │
                    │  (唯一，不监听任何队列)   │
                    └──────────┬────────────┘
                               │ 往知识源队列发任务消息
                               ▼
        ┌──────────────────────┼──────────────────────────┐
        ▼                      ▼                           ▼
  ┌───────────┐       ┌───────────────┐          ┌────────────────┐
  │  Car:001  │       │  Car:002      │          │Navigator4CarID │
  │  独立队列   │  ...  │  独立队列      │          │  共享队列 (×N)  │
  └─────┬─────┘       └──────┬────────┘          └──────┬─────────┘
        │                    │                          │
        └────────────────────┼──────────────────────────┘
                             │ 知识源读写黑板
                    ┌────────▼────────┐
                    │   黑板 (Redis)   │
                    └────────┬────────┘
                             │ Display 数读黑板
                    ┌────────▼────────┐
                    │  UpdateView     │  Fanout 广播
                    │  ┌─────▼──────┐ │
                    │  │ Display×N   │ │
                    │  │ 用户名/统计  │ │
                    │  │ 路径回放    │ │
                    │  └────────────┘ │
                    └─────────────────┘
```

### 为什么是黑板风格

| 关注点       | 黑板风格方案                         | 纯 P2P 方案                           |
| ------------ | ------------------------------------ | ------------------------------------- |
| 全局地图共享 | Redis bitmap，所有人读写同一份        | 每辆车维护局部地图，需要同步协议        |
| 冲突避免     | 中心锁 + 目标互斥                    | 分布式共识算法                         |
| 可视化       | Display 直接读黑板即可               | 需要汇总多个车的地图                   |
| 调试         | 单一数据源，容易排查                  | 状态分散，难追踪                       |
| 课程演示     | 黑板变化过程直观                     | 涌现行为难展示                         |

### Controller 的 C2 模式定位

**Controller 不监听任何 RabbitMQ 队列**，只做两件事：

1. **读黑板** — 循环读取黑板中的 FIFO 任务队列（`taskQueue`）
2. **写知识源队列** — 往 RabbitMQ 知识源队列发任务消息，间接调用知识源功能

```
Controller 循环:
  │
  ├─ 读黑板 taskQueue (FIFO)
  ├─ 根据队首任务类型分发:
  │     ├─ 车无路径 → 发消息到 Navigator4CarID 队列
  │     ├─ 车有路径 → 发"挪一步"消息到 Car:{id} 队列
  │     └─ 任务完成 → 出队，处理下一个
  │
  └─ 按间隔重复
```

这是 **C2 (Command & Control) 模式** + 黑板风格：Controller 通过消息总线间接调用知识源，知识源完成任务后写结果到黑板，Controller 再读黑板决定下一步。

---

## 三、模块职责与边界

```
BlackBoxAI/
├── common/                  共享基础设施
│   ├── BlackboardClient        Redis 读写封装（唯一黑板入口）
│   ├── MessageBusClient        RabbitMQ 收发封装
│   ├── ConfigConstants         所有 Key / 队列名 / 默认值
│   ├── Launcher                一键启动所有模块
│   └── model/                  Point, CarState, MQMessage, 枚举
│
├── task-configurator/        任务初始化器
│   └── 清空 Redis → 网格分区生成出生点 → 生成随机障碍物
│       → 初始化 taskQueue (FIFO) → 写 config:task
│
├── controller/               节奏协调器 (唯一实例)
│   └── 不监听任何队列，纯调度:
│       ├─ 循环读黑板 taskQueue (FIFO List)
│       ├─ 车无路径 → 往 Navigator4CarID 发导航任务
│       ├─ 车有路径 → 往 Car:{id} 发"挪一步"指令
│       └─ 探索率 ≥ 99.9% → 结束
│
├── navigator/                路径规划器 (1 个或多个)
│   ├── 共享竞争队列 Navigator4CarID
│   ├── 扫描汽车路径任务，若队列为空则跳过
│   ├── 随机选取未探索区域作为目的地
│   ├── 加权 BFS (优先未探索格) → 写入 car:{id}:route
│   └── PathPlanner 接口 + BFSPlanner (唯一实现)
│
├── car/                      小车代理 (每车一个进程)
│   ├── 独立队列 Car:{id}，接收"挪一步"指令
│   ├── 自己判断最优路径 (两步前瞻)
│   ├── 移动前检查目标点是否已被探索 → 已探索则放弃
│   ├── 移动 + 点亮 3×3 + 遇阻处理
│   ├── Illuminator             3×3 区域点亮
│   └── DynamicObstacleManager  障碍物标记 / 清除
│
└── display/                  可视化前端 (多个实例)
    ├── DisplayMain             内嵌 HTTP 服务器
    ├── 订阅 UpdateView 广播
    ├── 用户名标识、统计次数
    ├── 路径回放功能
    └── web/index.html          Canvas 地图渲染 (障碍物/路径/汽车)
```

### 关键设计约束

| 规则                         | 说明                                     |
| ---------------------------- | ---------------------------------------- |
| 只有 common 能直接调 Redis   | 其他模块通过 BlackboardClient 读写黑板    |
| Controller 不监听任何队列     | 只往知识源队列发消息（C2 间接调用）        |
| 知识源通过消息通信            | Car 独立队列 / Navigator 共享竞争队列     |
| 每个知识源模块独立进程        | Launcher 按顺序 fork，各自存活            |
| 小车之间不通信               | 通过黑板上的目标标记间接避碰              |
| Navigator 多实例共享队列      | RabbitMQ 天然竞争消费，自动负载均衡       |
| Display 多实例广播            | Fanout 模式，每个终端独立运作             |

### 模块启动顺序

```
Redis / RabbitMQ 就绪
  → TaskConfigurator    (写初始数据、初始化 taskQueue)
    → Navigator×N       (订阅 Navigator4CarID，共享竞争)
      → Car×N           (订阅各自的 Car:{id} 队列)
        → Controller    (写黑板 taskQueue，发知识源消息)
          → Display×N   (订阅 UpdateView 广播)
```

---

## 四、数据流与状态机

### 4.1 CarStatus 状态流转

```
                    ┌─────────────────────────┐
                    │         IDLE            │◄─────────────────┐
                    └──────────┬──────────────┘                  │
                               │                                  │
               Navigator 规划路径完成                              │
               Controller 往 Car:{id} 发"挪一步"                   │
                               │                                  │
                    ┌──────────▼──────────────┐                  │
                    │        MOVING            │                  │
                    │  (自主判断最优下一步)      │                  │
                    └──────────┬──────────────┘                  │
                               │                                  │
               ┌───────────────┼───────────────┐                 │
               │               │               │                 │
          目标已探索     路径有剩余        路径为空              │
               │               │               │                 │
     清路径+重新规划   车保持 MOVING      清路径+重新规划         │
               │               │               │                 │
               │         ┌─────┴─────┐         │                 │
               │         │ 遇阻?      │         │                 │
               │         └─────┬─────┘         │                 │
               │               │               │                 │
               │         车 → BLOCKED          │                 │
               │               │               │                 │
               └───────────────┼───────────────┘                 │
                               │                                  │
         车 → IDLE ────────────┴──────────────────────────────────┘
```

**说明**：Car 不再经过 WAITING_ROUTE / READY 状态。Controller 看到车有路径就直接发"挪一步"，车自己判断下一步怎么走。简化到只有 **IDLE / MOVING / BLOCKED** 三种状态。

### 4.2 Controller 主循环

```
Controller 循环 (每 tick):
  │
  ├─ 1. 读黑板: 探索率 ≥ 99.9%? → 结束
  │
  ├─ 2. 读黑板 taskQueue (FIFO List)，取队首任务
  │
  ├─ 3. 根据任务处理:
  │     │
  │     ├─ 任务类型 = 车需要路径
  │     │     └─ 往 Navigator4CarID 发送: {carId, 探索区域范围}
  │     │        Navigator 消费 → 随机选未探索点 → BFS → 写 car:{id}:route
  │     │
  │     ├─ 任务类型 = 车有路径待移动
  │     │     └─ 往 Car:{id} 发送: {type: "move_step"}
  │     │        Car 消费 → 自主判断下一步 → 移动 → 点亮 → 写黑板
  │     │
  │     └─ 任务类型 = 车遇阻
  │           └─ 检查阻塞时间，超时则清空路径，入队新任务
  │
  └─ 4. 往 UpdateView 发广播 → Display×N 刷新
```

### 4.3 Car 自主决策流程

```
Car 收到 "move_step" 消息:
  │
  ├─ 1. 获取当前路径 (car:{id}:route)
  │     ├─ 路径为空 → 往 taskQueue 入队"需要路径"任务 → return
  │     └─ 路径不为空 ↓
  │
  ├─ 2. 目标探索检查: 目标点 (car:{id}:target) 是否已被探索？
  │     ├─ 是 → 目标无效，clearRoute + clearTarget → 往 taskQueue 入队 ROUTE_NEEDED → return
  │     └─ 否 → 目标仍有价值，继续 ↓
  │
  ├─ 3. peekNextStep → 检查合法性 (障碍物/边界)
  │     ├─ 遇阻 → 车 → BLOCKED，往 taskQueue 入队"遇阻"任务
  │     └─ 合法 ↓
  │
  ├─ 4. popNextStep → 移动到新位置
  │
  ├─ 5. 点亮 3×3 (Illuminator)
  │
  └─ 6. 更新黑板: position, steps
```

### 4.4 Navigator 流程

```
Navigator 消费 Navigator4CarID 消息:
  │
  ├─ 1. 解析 carId
  │
  ├─ 2. 读取黑板: 地图尺寸、已探索 bitmap、障碍物 bitmap
  │
  ├─ 3. 扫描未探索区域:
  │     ├─ 全部已探索 → 跳过 (do nothing)
  │     └─ 还有未探索 ↓
  │
  ├─ 4. 随机选取一个未探索格子作为目的地
  │
  ├─ 5. 加权 BFS 规划路径 (start → target)
  │       未探索格权重=1 (优先), 已探索格权重=2 (可走不优先)
  │       障碍物跳过
  │
  ├─ 6. 写 car:{id}:route 到黑板 (pushRoute)
  │
  └─ 7. 往 taskQueue 入队: {carId, 有路径待移动}
```

---

## 五、FIFO 任务队列设计

### 5.1 数据结构

使用 Redis List 存储，Key: `taskQueue`

```
taskQueue = [
    {type: "ROUTE_NEEDED",  carId: "Car001"},
    {type: "MOVE_READY",    carId: "Car002"},
    {type: "BLOCKED",       carId: "Car001", blockedTick: 42},
    ...
]
```

Controller 用 `LPOP` 取队首（FIFO），知识源完成处理后 `RPUSH` 新任务到队尾。

### 5.2 任务类型

| 任务类型       | 入队者         | Controller 处理动作              |
| -------------- | -------------- | -------------------------------- |
| `ROUTE_NEEDED` | Car (路径空)   | 往 Navigator4CarID 发导航任务     |
| `MOVE_READY`   | Navigator      | 往 Car:{id} 发"挪一步"指令        |
| `BLOCKED`      | Car (遇阻)     | 超时判定 → 清空路径 → 重新入队    |

---

## 六、Display 多终端设计

### 6.1 架构

每个 Display 终端是一个独立进程，启动时指定端口号：

```
Launcher → Display(8888) → Display(8889) → Display(8890) ...
```

### 6.2 功能

| 功能       | 说明                                         |
| ---------- | -------------------------------------------- |
| 用户名     | 每个终端登录时输入用户名，标识谁在看           |
| 地图渲染   | Canvas 绘制：已探索 (绿) / 障碍物 (黑) / 未知 (灰) |
| 汽车渲染   | 实时显示每辆车的位置、状态                     |
| 预测路径   | 可选开关：显示指定汽车的规划路径 (careId+Route) |
| 统计面板   | 探索百分比、总步数、各车步数、运行时间         |
| 路径回放   | 记录每 tick 的快照 → 可拖动时间轴回放历史轨迹  |

### 6.3 广播消息

| 消息内容                    | 触发时机       | 作用                     |
| --------------------------- | -------------- | ------------------------ |
| `{type: "All"}`             | 每 tick 结束    | 刷新所有内容             |
| `{type: "Route", carId}`    | 用户点击某车    | 显示该车的预测路径        |

### 6.4 路径回放

Display 端维护一个快照队列（内存，最多保留 500 个 tick）：

```
snapshots = [
    {tick: 0, cars: [...], explored: 12%},
    {tick: 1, cars: [...], explored: 15%},
    ...
]
```

前端提供时间轴滑块，拖动时渲染对应 tick 的快照。

---

## 七、关键算法

### 7.1 多车出生点：网格分区

**目标**：将 N 辆车均匀分散到地图不同区域，避免扎堆中心，提升并行探索效率。

```
1. 计算网格划分:
   列数 cols = ceil(sqrt(N))
   行数 rows = ceil(N / cols)
   每格宽度 cellW = mapWidth / cols
   每格高度 cellH = mapHeight / rows

2. 每个格子中心 = (col * cellW + cellW/2, row * cellH + cellH/2)

3. 对每辆车 i（按格子 row, col 顺序分配）：
   - 目标出生点 = 格子中心
   - 如果目标点或周边被障碍物占据，在格内螺旋搜索最近的空地
   - 写入 car:{id}:position
```

**示例（4 辆车，30×30 地图）**：

```
 ┌──────────────┬──────────────┐
 │   Car001     │   Car002     │
 │   (7, 7)     │   (22, 7)    │
 ├──────────────┼──────────────┤
 │   Car003     │   Car004     │
 │   (7, 22)    │   (22, 22)   │
 └──────────────┴──────────────┘
```

### 7.2 目标探索检查（避免死循环）

**目标**：车移动前检查目标点是否已被其他车探索过。如果目标已被探索，当前路径失去价值，放弃并重新请求。

**为什么不检查"路径全亮"而是只检查目标点？**

```
考虑车A走在半路上，路径中间的点被车B点亮了:

  如果检查"路径全亮":
    中间亮了几格 → 放弃 → 重新规划 → 又走一半又被点亮 → 放弃 → ...
    → 死循环：车A永远走不到终点

  如果只检查"目标点是否被探索":
    中间格子亮了不影响，只要目标还是未知的，路径就有价值
    车A走到底把目标点亮 → 任务完成
    → 不会死循环，保证渐进覆盖
```

```
算法:
1. 读取 car:{id}:target
2. 检查 isExplored(target.x, target.y)
3. 如果目标已被探索：
   ├─ clearRoute() + clearCarTarget()
   ├─ setCarStatus(IDLE)
   └─ 往 taskQueue 入队 ROUTE_NEEDED 任务
4. 如果目标仍未被探索：
   └─ 继续正常移动（路径中间已探索的格子走过也无妨）
```

**触发场景**：

```
   车A正在往未探索点 D 移动: A → a → b → c → D (D 未探索)
   车B 移动中把 D 点亮了
   → 车A 检查发现目标 D 已被探索 → 放弃剩余路径
   → Navigator 重新分配新目标 E
```

### 7.3 加权 BFS：优先走未探索区域

**目标**：BFS 规划路径时，优先经过未探索格子，在前往目标的同时顺便探索新区域。

**算法**（基于 0-1 BFS 思想，使用双端队列替代普通队列）：

```
BFS 规划 (start → target):

  使用 Deque<Point> 代替 Queue

  对于每个邻居:
    if 障碍物 → 跳过
    if 未探索 → 权重 = 1 (cost=1)，加到队尾
    if 已探索 → 权重 = 2 (cost=2)，加到队尾
                   但优先走未探索路径

  优化：未探索邻居插入队首 (cost=0 relative)，已探索插入队尾 (cost=1 relative)
  即 0-1 BFS: 未探索格相当于边权 0，已探索格相当于边权 1
```

**效果对比**：

```
普通 BFS (不区分):        加权 BFS (优先未探索):
─────────────────────     ─────────────────────
S → x → x → x → T        S → ? → ? → ? → T
x = 已探索走过老路          ? = 未探索，走新路顺便点亮

结果: 车只到达目标         结果: 车到达目标 + 沿途解锁新格子
```

**实现要点**：
- 从 `Queue` 切换为 `Deque`（双端队列）
- 未探索邻居 → `offerFirst`（优先处理）
- 已探索邻居 → `offerLast`（延后处理）
- 等价于 BFS 在 0-1 图上的最短路径

### 7.4 两步前瞻（Car 自主判断最优路径）

车收到"挪一步"指令后，不只是机械走路径队列的下一步，而是对路径上前两步做前瞻检查：

```
1. peekNextStep (第 1 步) → 检查合法性
2. 合法则 popNextStep，移动到第 1 步
3. peekNextStep (第 2 步) → 预判合法性
4. 如果第 2 步已被其他车占据或有障碍：
   └─ 不继续走，清空剩余路径 → 重新请求路径
5. 如果第 2 步合法：
   └─ 继续下一次"挪一步"时走
```

---

## 八、性能设计

### 8.1 地图读取优化

**问题**：Navigator 每次规划路径都需要读取全量地图 bitmap，`getBitmapAsGrid` 将整个 bitmap 解码为 boolean[][]，**O(W×H)** 开销大。

**优化方案**：

| 场景               | 当前                                       | 优化后                                   |
| ------------------ | ------------------------------------------ | ---------------------------------------- |
| Navigator 读障碍物 | 每次全量 `getMapBlocked()`                  | 缓存 bitmap 字节数组，仅在障碍物变化时刷新 |
| Display 刷新       | 每 tick 全量 `getMapView()`                | 增量更新：只传变化的 bit 位               |
| 探索率计算         | `BITCOUNT` 每次全量                        | 同，BITCOUNT 本身是 O(1) Redis 操作       |
| 未探索区域扫描     | Navigator 遍历 boolean[][] 全量 O(W×H)     | 维护 Redis Set 记录未探索坐标，随机取一个  |

**具体优化**：

1. **BlackboardClient 增加 bitmap 缓存**：
```java
// 缓存最近一次读取的 bitmap 字节数组 + 最后更新时间
private byte[] cachedBlockedBitmap;
private long blockedBitmapVersion;

public boolean[][] getMapBlocked() {
    long currentVersion = getBitmapVersion(KEY_MAP_BLOCKED);
    if (cachedBlockedBitmap != null && currentVersion == blockedBitmapVersion) {
        return decodeBitmap(cachedBlockedBitmap, mapWidth, mapHeight);
    }
    // 重新读取并缓存
    byte[] data = jedis.get(KEY_MAP_BLOCKED.getBytes());
    cachedBlockedBitmap = data;
    blockedBitmapVersion = currentVersion;
    return decodeBitmap(data, mapWidth, mapHeight);
}
```

2. **未探索区域索引**：Navigator 不再遍历全量 boolean[][] 找未探索点，改为从 Redis Set `unexplored:set` 随机取一个。Car 的 Illuminator 点亮时同步 `SREM`。

### 8.2 其他性能考量

| 关注点           | 方案                                           |
| ---------------- | ---------------------------------------------- |
| Navigator 多实例 | 共享竞争队列，水平扩展                          |
| RabbitMQ 连接    | 每个模块独立 Channel，复用 TCP 连接             |
| Redis 连接池     | JedisPool 最大 20 连接，够了                   |
| Display 广播     | Fanout 模式，不复制消息，交换机级路由           |

---

## 九、技术栈与关键配置

### 9.1 技术选型

| 层次       | 选型                   | 说明                          |
| ---------- | ---------------------- | ------------------------------ |
| 语言       | Java 17                | 课程统一要求                    |
| 构建       | Maven 多模块            | 一个 parent pom，6 个子模块     |
| 黑板       | Redis 7 (Jedis 5.2)    | Bitmap 存地图，List 存路径      |
| 消息总线   | RabbitMQ 3.x           | 每个模块独立队列，AMQP 协议     |
| 序列化     | Fastjson2 2.0.53       | JSON 格式，人与机器都可读       |
| 日志       | SLF4J + slf4j-simple   | 各模块独立输出日志              |
| 可视化     | 内嵌 HTTP + Canvas     | 端口可指定，浏览器打开即看      |
| 启动       | Launcher 一键 fork     | 自动检测 JDK + Maven 依赖路径   |

### 9.2 Redis Key 约定

| Key                  | 类型               | 写入者                       | 说明              |
| -------------------- | ------------------ | ---------------------------- | ----------------- |
| `map:view`           | Bitmap             | Illuminator                  | 已探索 = 1        |
| `map:blocked`        | Bitmap             | TaskConfigurator, Car        | 障碍物 / 车 = 1   |
| `taskQueue`          | List (FIFO)        | Controller, Car, Navigator   | 任务队列           |
| `car:{id}:status`    | String             | Controller, Car              | IDLE/MOVING/BLOCKED |
| `car:{id}:position`  | Hash {x, y}        | TaskConfigurator, Car        | 当前坐标           |
| `car:{id}:route`     | List (LPUSH/RPOP)  | Navigator, Car               | 路径队列           |
| `car:{id}:target`    | String (JSON)      | Navigator                    | 目的地              |
| `car:{id}:steps`     | String             | Car                          | 行驶步数           |
| `car:{id}:blocked_tick` | String          | Car                          | 受阻 tick          |
| `config:task`        | Hash               | TaskConfigurator             | 全局配置           |
| `lock:car:{id}`      | String             | Controller, Car, Navigator   | 分布式锁           |
| `unexplored:set`     | Set                | TaskConfigurator, Illuminator | 未探索坐标索引     |

### 9.3 RabbitMQ 队列划分

| 队列                | 模式       | 消费者              | 说明               |
| ------------------- | ---------- | ------------------- | ------------------ |
| `Navigator4CarID`   | 共享竞争   | Navigator ×N        | 多实例自动负载均衡   |
| `Car:{id}`          | 独立点对点 | Car:{id}            | 每车一个独立队列    |
| `UpdateView`        | Fanout     | Display ×N          | 广播所有显示终端    |

---

## 十、消息类型汇总

| 消息            | 方向            | 触发条件                        | 处理动作                                |
| --------------- | --------------- | ------------------------------- | -------------------------------------- |
| `NAVIGATE`      | Ctrl → Nav      | taskQueue 有 ROUTE_NEEDED       | Navigator 随机选未探索点 → 加权BFS → 写路径 |
| `MOVE_STEP`     | Ctrl → Car      | taskQueue 有 MOVE_READY         | Car 自主判断 → 移动 → 点亮              |
| `ALL`           | Ctrl → Display  | 每 tick 结束                    | Display 刷新全部内容                    |
| `ROUTE_DISPLAY` | Ctrl → Display  | 用户点击汽车                    | Display 显示该车预测路径                |