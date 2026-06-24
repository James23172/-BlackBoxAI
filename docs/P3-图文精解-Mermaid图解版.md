# P3 图文精解：名词术语大全 + Mermaid 可视化

> **整合**：`P3-零基础完整教学文档.md` + `P3-深度展开-完整技术剖析.md` + `P3-代码完全注解版.md`
> **内容**：所有专业名词详解 + 所有 ASCII 图 → Mermaid 代码（可直接渲染为图像）
> **作者**：WorkBuddy | 2026-06-23

---

# 目录

1. [核心名词术语大全](#一核心名词术语大全)
2. [系统全景 Mermaid 图解](#二系统全景-mermaid-图解)
3. [消息流 Mermaid 图解](#三消息流-mermaid-图解)
4. [状态机 Mermaid 图解](#四状态机-mermaid-图解)
5. [算法流程 Mermaid 图解](#五算法流程-mermaid-图解)
6. [数据结构 Mermaid 图解](#六数据结构-mermaid-图解)
7. [并发模型 Mermaid 图解](#七并发模型-mermaid-图解)
8. [完整任务生命周期时序图](#八完整任务生命周期时序图)

---

# 一、核心名词术语大全

## 1.1 架构与设计模式

### 黑板模式（Blackboard Pattern）
一种软件架构模式。多个独立的"知识源"（模块）通过一个共享的"黑板"（共享存储）交换信息，各模块之间不直接通信。好处是模块完全解耦，一个模块崩溃不影响其他模块。

> 项目体现：Redis 是黑板，所有模块只读写 Redis，模块间无直接调用。

### C2 架构（Command and Control）
源自军事指挥体系。由一个中央控制器（C）向多个执行器（2...N）下发命令，执行器不主动行动。Controller 是唯一的大脑。

> 项目体现：Controller 下达 NAVIGATE/MOVE_STEP/FORWARD_CONFIG 命令，其他模块被动响应。

### 分布式锁（Distributed Lock）
在多进程/多机器环境下，确保同一时刻只有一个进程能执行某段代码。基于 Redis SET NX EX 实现：NX(Not eXists) 保证互斥，EX(Expire) 设置过期时间防止死锁。

### 拉式任务驱动（Pull-based Task Queue）
消费者主动从队列拉取任务，而不是被推送。好处是消费者可以控制处理速度，防止过载。

> 项目体现：Controller 通过 BLPOP 从 Redis List 拉任务，而非订阅 RabbitMQ 队列被推送。

### 事件驱动（Event-driven）
程序的执行流程由"事件"（消息到达、用户点击、定时器触发）驱动，而非按预设顺序执行。

> 项目体现：TaskConfigurator 通过 RabbitMQ DeliverCallback 回调被触发；Controller 通过 tick 定时器和 Redis 轮询触发。

---

## 1.2 中间件与基础设施

### Redis
开源的内存数据结构存储系统，支持 String/Hash/List/Set/Bitmap 等多种数据结构。所有数据在内存中，读写极快（微秒级）。支持持久化到磁盘（RDB 快照）。

**本项目中的 5 种用法：**

| 数据结构 | 用途 | Redis 命令 |
|---------|------|-----------|
| **String (BIT)** | 地图探索/障碍物 bitmap | SETBIT / GETBIT / BITCOUNT |
| **Hash** | 小车位置 {x, y}、任务配置 | HSET / HGET / HGETALL |
| **List** | 任务队列、小车路径、回放快照 | RPUSH / LPOP / BLPOP / LRANGE |
| **Set** | 未探索坐标索引 ("x,y") | SADD / SREM / SRANDMEMBER / SCARD |
| **String (lock)** | 分布式锁 | SET NX EX / DEL |

### Redis Pipeline
将多条 Redis 命令在客户端缓冲，一次性发送给服务端执行，再一次性接收结果。减少网络往返次数，大幅提升批量操作性能。

### Redis bitmap
Redis 的位图数据结构。每个 bit 存储一个布尔值（0/1），通过 `SETBIT key offset value` 设置，`GETBIT key offset` 读取，`BITCOUNT key` 统计 1 的数量。

> 项目体现：40×40=1600 格，只需 200 字节，O(1) 读写单格，O(字节数) 统计。

### Redis BGSAVE
Redis 的后台持久化命令。fork 子进程将当前内存数据写入 RDB 文件，主进程继续服务，不阻塞。

### 版本号缓存（Version-based Cache）
一种缓存失效策略。写入数据时递增版本号，读取时对比缓存的版本号：相同则直接返回缓存，不同则重新读取。避免高频率重复读取不变的数据。

> 项目体现：map:view bitmap 每 100ms 读取一次，但只在车移动时才变化，版本号缓存将性能提升 100x。

### RabbitMQ
开源的消息队列中间件。生产者向 Exchange 发消息，Exchange 按规则路由到 Queue，消费者从 Queue 拉消息。

**本项目中的核心概念：**

| 概念 | 解释 | 项目中的例子 |
|------|------|------------|
| **Exchange** | 消息交换机，接收消息并路由到队列 | UpdateView(Fanout) |
| **Queue** | 消息队列，存储消息直到被消费 | Navigator4CarID, Car:Car001 |
| **Fanout Exchange** | 广播模式：消息发给所有绑定的队列 | Display 同步刷新 |
| **Consumer** | 消费者，从队列取消息处理 | Navigator, Car |
| **basicAck** | 手动确认：消费者处理完后告知 RabbitMQ | TaskConfigurator 防消息丢失 |
| **durable** | 持久化：RabbitMQ 重启后队列/消息不丢失 | 所有队列都是 durable=true |

### RabbitMQ 自动恢复（Automatic Recovery）
RabbitMQ 客户端的功能：当连接意外断开后自动重连并恢复队列/Exchange 绑定，无需手动干预。

### RabbitMQ 公平分发（basicQos）
`basicQos(1)` 告诉 RabbitMQ 每次只给一个消费者发一条消息，处理完确认后再发下一条。避免忙的消费者更忙、闲的更闲。

---

## 1.3 Java 并发

### volatile 关键字
Java 的关键字。修饰变量时，保证该变量的读写对所有线程立即可见（强制从主内存读写，而非 CPU 缓存）。用于多线程共享的布尔标志。

> 项目体现：ControllerAgent 的 `running`、`taskActive`、`recording` 都被不同线程读写，必须加 volatile。

### synchronized 关键字
Java 的内置锁机制。`synchronized(obj) { ... }` 表示只有获取 obj 监视器锁的线程才能进入代码块。

### wait / notify / notifyAll
Java 线程间的等待/通知机制。

- `obj.wait()`：当前线程释放 obj 的锁，进入 WAITING 状态（休眠）
- `obj.notifyAll()`：唤醒所有在 obj 上等待的线程

> 项目体现：taskProcessLoop 在 taskActive=false 时 wait(1000)，handleStartTask 调用 notifyAll 立即唤醒。

### 守护线程（Daemon Thread）
Java 的一种线程类型。当所有非守护线程结束时，JVM 自动退出，不等待守护线程。`thread.setDaemon(true)` 设置。

> 项目体现：taskProcessor 是守护线程，避免 JVM 无法退出。

### ScheduledExecutorService
Java 的定时任务调度器。`scheduleAtFixedRate(task, delay, period, unit)` 以固定频率执行任务。

> 项目体现：broadcastScheduler 每 100ms 执行一次 broadcastTick。

### try-with-resources
Java 7+ 的语法糖。`try (Resource r = ...) { ... }` 中的资源在代码块结束后自动调用 `close()`，无论是否异常。

> 项目体现：`try (Jedis jedis = pool.getResource())` 自动归还 Redis 连接。

### Lambda 表达式
Java 8+ 的函数式编程语法。`(参数) -> { 函数体 }`，用于简化匿名类的写法。

> 项目体现：`DeliverCallback cb = (tag, delivery) -> { ... }` 等价于匿名内部类。

---

## 1.4 算法与数据结构

### BFS（广度优先搜索）
从起点开始，按"距离层"逐层扩展访问所有可达节点的图搜索算法。普通 BFS 所有边权为 1，保证找到最短路径。

### 0-1 BFS
BFS 的变种。边权只有 0 和 1。使用双端队列（Deque）：cost=0 的节点放队头优先处理，cost=1 的放队尾。时间复杂度 O(V)，比 Dijkstra 快。

> 项目体现：未探索格 cost=0（优先走），已探索格 cost=1（不优先），同时保证整体路径最短。

### 曼哈顿距离（Manhattan Distance）
网格地图上的距离：`|x1-x2| + |y1-y2|`。因为不能斜走，总步数 = 水平差 + 垂直差。

### 贪心算法（Greedy Algorithm）
每一步都选当前最优解，不保证全局最优。优点是计算开销极小。

> 项目体现：TargetPlanner 每次选最近未探索格作为目标。

### 拒绝采样（Rejection Sampling）
随机生成候选，检查是否符合条件，不符合则拒绝对重新生成。适合约束条件下生成随机样本。

> 项目体现：障碍物随机生成时，每次随机选坐标，检查是否在禁区，不在才接受。

### 两步前瞻（Two-step Lookahead）
在做出决策前不仅检查下一步，还检查第二步。如果第二步有问题（障碍物），提前终止当前路径。

> 项目体现：Car 移动前 peek 第二步，如有障碍则清空剩余路径，避免无意义的移动。

### 网格分区（Grid Partitioning）
将矩形地图划分为规则网格，每个格子放置一个对象。确保对象均匀分布。

> 项目体现：出生点计算 `cols=⌈√N⌉, rows=⌈N/cols⌉`，每辆车放在对应格子的中心。

---

## 1.5 系统设计名词

### FIFO（First In, First Out）
先进先出。队列的一种特性：先入队的元素先出队。

> 项目体现：Redis List RPUSH(入队尾) + LPOP(出队头) 实现 taskQueue。

### BLPOP（Blocking Left Pop）
Redis 的阻塞式列表弹出命令。如果 List 为空，客户端阻塞等待直到有新元素或超时。实现事件驱动架构的关键。

### 轮询（Polling）
定期检查某个条件是否满足。优点是简单，缺点是有延迟和 CPU 浪费。

> 项目体现：taskProcessLoop 每 1 秒轮询 Redis taskActive（混合了事件唤醒）。

### 连续清空（Drain Loop）
处理完一条消息后，继续用非阻塞方式清空队列中的剩余消息，避免积压。

> 项目体现：BLPOP 拉一条 + while(popTask()!=null) 连续清空。

### 崩溃恢复（Crash Recovery）
模块崩溃后重启时，能够正确恢复状态继续工作，不破坏已有数据。

> 项目体现：TaskConfigurator 重启时检测 existingConfig，跳过初始化，触发 BGSAVE。

### 多实例分片（Multi-instance Sharding）
多个相同的服务实例通过某种规则（如取模）瓜分工作负载，互不冲突。

> 项目体现：Controller 通过 `carIndex % totalInstances == instanceId` 分片管理小车。

### 双保险判定（Double-check Completion）
用两个独立的指标（SCARD + BITCOUNT）来判断任务是否完成，防止单一指标失效导致误判。

### 双层暂停（Two-level Pause）
全局暂停（配置员）覆盖个人暂停（运行员）。先检查全局，再检查个人。

### 幂等操作（Idempotent）
多次执行与一次执行效果相同的操作。

> 项目体现：`queueDeclare` 声明已存在的队列不是错误。

---

# 二、系统全景 Mermaid 图解

## 2.1 整体架构总览

```mermaid
graph TB
    subgraph Browser["🖥 浏览器 Chrome"]
        UI["index.html<br/>Canvas 渲染 + WebSocket"]
    end

    subgraph Display["Display 模块 :8888/:8887"]
        HTTP["HTTP Server :8888<br/>静态文件 + 配置API"]
        WS["WebSocket :8887<br/>CommandReceiver + StateBroadcaster"]
        Fanout["Fanout 订阅<br/>接收 REFRESH_ALL"]
    end

    subgraph Core["核心调度层"]
        Controller["Controller<br/>全局调度器<br/>taskProcessLoop + broadcastTick"]
        TC["TaskConfigurator<br/>任务初始化器<br/>地图/障碍物/小车"]
    end

    subgraph Knowledge["知识源层"]
        Nav["Navigator<br/>路径规划 BFS/A*"]
        TP["TargetPlanner<br/>贪心目标选择"]
        Car1["Car:Car001"]
        Car2["Car:Car002"]
        Car3["Car:Car003"]
        Car4["Car:Car004"]
    end

    subgraph Infra["基础设施"]
        Redis[("Redis 黑板<br/>map:view / car:*<br/>taskQueue / unexplored:set")]
        MQ["RabbitMQ 消息总线<br/>Navigator4CarID / Car:*<br/>TargetPlannerCmd / UpdateView"]
        Auth["Auth Server :8890"]
        Replay["Replay Server :8893"]
    end

    UI -->|"WebSocket"| WS
    WS -->|"用户操作"| Controller
    WS -->|"写入"| Redis
    
    Controller -->|"BLPOP 拉取"| Redis
    Controller -->|"NAVIGATE"| MQ
    Controller -->|"MOVE_STEP"| MQ
    Controller -->|"FORWARD_CONFIG"| MQ
    Controller -->|"REFRESH_ALL"| MQ
    
    MQ -->|"消费"| Nav
    MQ -->|"消费"| TP
    MQ -->|"消费"| Car1
    MQ -->|"消费"| Car2
    MQ -->|"消费"| Car3
    MQ -->|"消费"| Car4
    MQ -->|"消费"| TC
    
    Nav -->|"读写"| Redis
    TP -->|"读写"| Redis
    Car1 -->|"读写"| Redis
    
    MQ -->|"Fanout"| Fanout
    Fanout -->|"广播状态"| WS
    
    Auth -->|"读写"| Redis
    Replay -->|"读"| Redis

    style Browser fill:#e1f5fe,stroke:#0288d1
    style Display fill:#fff3e0,stroke:#f57c00
    style Core fill:#fce4ec,stroke:#c62828
    style Knowledge fill:#e8f5e9,stroke:#2e7d32
    style Infra fill:#f3e5f5,stroke:#7b1fa2
```

## 2.2 P3 职责边界图

```mermaid
graph LR
    subgraph P3["P3 负责的区域"]
        direction TB
        A1["1. 地图生成<br/>40×40 网格 + 障碍物<br/>网格分区出生点"]
        A2["2. 小车初始化<br/>Car001~Car004<br/>IDLE 状态 + 点亮出生点"]
        A3["3. 任务队列管理<br/>接收 START/PAUSE<br/>处理 ROUTE_NEEDED/MOVE_READY"]
        A4["4. 命令分发<br/>NAVIGATE → Navigator<br/>MOVE_STEP → Car"]
        A5["5. 周期性驱动<br/>100ms tick<br/>探索完成检测<br/>受阻超时恢复<br/>快照录制<br/>广播刷新"]
    end

    subgraph Others["P3 不负责"]
        direction TB
        B1["路径规划 → Navigator"]
        B2["目标选择 → TargetPlanner"]
        B3["实际移动 → Car"]
        B4["画面渲染 → Display"]
    end

    P3 -.- |"职责边界"| Others

    style P3 fill:#e3f2fd,stroke:#1565c0
    style Others fill:#fce4ec,stroke:#c62828
```

---

# 三、消息流 Mermaid 图解

## 3.1 完整消息流转（从点击"开始"到探索完成）

```mermaid
sequenceDiagram
    actor User as 👤 用户
    participant Display as Display
    participant Redis as Redis 黑板
    participant Controller as Controller
    participant MQ as RabbitMQ
    participant TC as TaskConfigurator
    participant Nav as Navigator
    participant TP as TargetPlanner
    participant Car as Car Agent

    Note over User,Car: === 初始化阶段 ===

    User->>Display: 1. 点击"应用配置"
    Display->>Redis: 2. RPUSH taskQueue SET_CONFIG
    Controller->>Redis: 3. BLPOP taskQueue → SET_CONFIG
    Controller->>MQ: 4. FORWARD_CONFIG → TaskConfigCmd
    MQ->>TC: 5. 收到 FORWARD_CONFIG
    
    Note over TC: 6. clearAll() + 生成地图<br/>+ 随机障碍物 + 初始化小车<br/>+ 推入 ROUTE_NEEDED
    
    TC->>Redis: 7. 写入 config:task + car:*<br/>+ taskQueue

    Note over User,Car: === 启动阶段 ===

    User->>Display: 8. 点击"开始"
    Display->>Redis: 9. RPUSH taskQueue START
    Controller->>Redis: 10. BLPOP taskQueue → START
    Controller->>Redis: 11. setTaskActive(true)

    Note over User,Car: === 调度循环 ===

    loop 直到 unexplored:set 为空
        Controller->>Redis: 12. BLPOP taskQueue → ROUTE_NEEDED
        Controller->>MQ: 13. NAVIGATE → Navigator4CarID
        MQ->>Nav: 14. 收到 NAVIGATE
        
        Nav->>MQ: 15. GET_TARGET → TargetPlannerCmd
        MQ->>TP: 16. 收到 GET_TARGET
        TP->>Redis: 17. SRANDMEMBER unexplored:set
        
        Note over TP: 贪心选最近未探索格
        
        TP->>Redis: 18. 写入 car:{id}:target
        TP->>Redis: 19. RPUSH taskQueue ROUTE_NEEDED
        
        Controller->>MQ: 20. NAVIGATE → Navigator4CarID
        Nav->>MQ: 21. BFS/A* 规划路径
        
        Note over Nav: 0-1 BFS：未探索优先
        
        Nav->>Redis: 22. pushRoute + 设 IDLE
        Nav->>Redis: 23. RPUSH taskQueue MOVE_READY
        
        Controller->>Redis: 24. BLPOP taskQueue → MOVE_READY
        
        Note over Controller: 双层暂停检查<br/>全局 → 个人
        
        Controller->>MQ: 25. MOVE_STEP → Car:{id}
        MQ->>Car: 26. 收到 MOVE_STEP
        
        Note over Car: 两步前瞻<br/>移动 + 3×3 点亮
        
        Car->>Redis: 27. 更新位置 + illuminateArea
        Controller-->>MQ: 28. 100ms tick REFRESH_ALL
        MQ-->>Display: 29. Fanout 广播
        Display-->>User: 30. WebSocket 推送状态<br/>Canvas 重绘
    end

    Note over Controller: unexplored:set=0<br/>探索率≥99.9%<br/>→ 🏁 巡检完成！
```

## 3.2 P3 内部消息路由（Controller 的任务分发）

```mermaid
graph TD
    Queue["Redis taskQueue<br/>FIFO 列表"] -->|"BLPOP"| Dispatch{"processTask()<br/>任务分发"}

    Dispatch -->|"type=START"| Start["handleStartTask()<br/>激活任务或触发初始化"]
    Dispatch -->|"type=PAUSE"| Pause["handlePauseTask()<br/>暂停任务处理"]
    Dispatch -->|"type=SET_CONFIG"| SetCfg["handleSetConfigTask()<br/>转发配置(不重建)"]
    Dispatch -->|"type=RESET"| Reset["handleResetTask()<br/>强制重建(forceReset)"]
    Dispatch -->|"type=RECORD_START/STOP"| Record["recording=true/false"]
    
    Dispatch -->|"type=ROUTE_NEEDED"| Route["requestNavigate(carId)<br/>→ NAVIGATE → Navigator4CarID"]
    Dispatch -->|"type=MOVE_READY"| Move{"双层暂停检查"}
    Dispatch -->|"type=BLOCKED"| Blocked["handleBlockedTimeout(carId)<br/>超时恢复"]
    Dispatch -->|"type=ADD_CAR"| AddCar["requestNavigate(carId)"]

    Move -->|"全局暂停?"| Skip1["跳过"]
    Move -->|"个人暂停?"| Skip2["跳过"]
    Move -->|"通过"| SendMove["→ MOVE_STEP → Car:{id}"]

    style Dispatch fill:#fff9c4,stroke:#f9a825
    style Move fill:#ffccbc,stroke:#e64a19
```

## 3.3 TaskConfigurator 的 handleForwardConfig 三路分发

```mermaid
graph TD
    MQ["RabbitMQ TaskConfigCmd"] -->|"FORWARD_CONFIG 消息"| Parse["解析 MQMessage"]

    Parse --> Check1{"addCar?"}
    Check1 -->|"是"| AddCar["增量添加小车<br/>addCar(carId, cx, cy)<br/>return"]
    Check1 -->|"否"| Check2{"removeCar?"}
    Check2 -->|"是"| RemoveCar["增量移除小车<br/>removeCar(carId)<br/>return"]
    Check2 -->|"否"| Check3{"forceReset?<br/>或 首次?"}
    Check3 -->|"否(崩溃恢复)"| Recover["跳过初始化<br/>BGSAVE 持久化<br/>return"]
    Check3 -->|"是"| FullInit["全量初始化"]

    FullInit --> S1["1. clearAll()"]
    S1 --> S2["2. initUnexploredSet()"]
    S2 --> S3["3. computeSpawnPoints()<br/>网格分区出生点"]
    S3 --> S4["4. buildForbiddenZone()<br/>出生点 3×3 禁区"]
    S4 --> S5["5. generateObstacles()<br/>拒绝采样 + Pipeline批量写"]
    S5 --> S6["6. 初始化小车<br/>位置/状态/步数/点亮"]
    S6 --> S7["7. setTaskConfig()<br/>写入 config:task Hash"]
    S7 --> S8["8. 推入 ROUTE_NEEDED<br/>每车一个"]
    S8 --> S9["9. declareAllSystemQueues()"]
    S9 --> S10["10. BGSAVE 持久化"]

    style Check1 fill:#e8f5e9,stroke:#2e7d32
    style Check2 fill:#e8f5e9,stroke:#2e7d32
    style Check3 fill:#fff3e0,stroke:#f57c00
    style FullInit fill:#e3f2fd,stroke:#1565c0
```

---

# 四、状态机 Mermaid 图解

## 4.1 小车三状态流转

```mermaid
stateDiagram
    [*] --> IDLE : 初始化 / 路径耗尽 / 受阻恢复
    
    IDLE --> MOVING : Controller 发 MOVE_STEP
    MOVING --> IDLE : 路径走完 / 第二步前瞻有障碍
    MOVING --> BLOCKED : 下一步有障碍物
    BLOCKED --> IDLE : 超时 200ms 后 Controller 恢复

    note right of IDLE
        空闲等待命令
        可被 tickDriveCars 驱动
    end note

    note right of MOVING
        正在执行移动
        不会被重复发命令
    end note

    note right of BLOCKED
        前方有障碍
        等待超时重规划
    end note
```

## 4.2 taskActive 激活状态机

```mermaid
stateDiagram
    [*] --> Inactive : Controller.start() → setTaskActive(false)

    Inactive --> Active : 用户点"开始"<br/>Redis taskActive=true<br/>wakeTaskProcessor()
    Active --> Inactive : 用户点"暂停"<br/>taskActive=false
    Active --> Completed : unexplored:set 为空<br/>探索率≥99.9%
    Completed --> [*] : 任务完成

    note right of Active
        taskProcessLoop 处理任务
        broadcastTick 100ms 周期
    end note

    note right of Inactive
        taskProcessLoop wait(1000)
        每 1 秒检查 Redis
    end note
```

## 4.3 小车状态 + 任务类型联合状态机

```mermaid
stateDiagram
    state "IDLE" as IDLE {
        [*] --> WaitRoute : 等待路径
        WaitRoute --> HasRoute : ROUTE_NEEDED →<br/>Controller → NAVIGATE →<br/>Navigator 规划路径
    }

    state "MOVING" as MOVING {
        [*] --> StepCheck : 收到 MOVE_STEP
        StepCheck --> MoveOk : 下一步无障碍
        StepCheck --> BlockedNow : 下一步有障碍
        MoveOk --> StepDone : 移动 + 点亮 + 步数+1
        StepDone --> LookAhead : 两步前瞻检查第二步
        LookAhead --> ContinueMove : 第二步合法 → IDLE
        LookAhead --> ClearPath : 第二步有障碍 → 清空路径
    }

    state "BLOCKED" as BLOCKED {
        [*] --> Waiting : 记录 blockedTick
        Waiting --> Timeout : diff ≥ 2 tick (200ms)
        Timeout --> Recover : 分布式锁 → 清路径/目标
    }

    IDLE --> MOVING : processTask(MOVE_READY)<br/>或 tickDriveCars()
    MOVING --> IDLE : 路径走完
    MOVING --> BLOCKED : 下一步有障碍
    BLOCKED --> IDLE : 超时恢复
    IDLE --> IDLE : 无路径 / 暂停中
```

## 4.4 完整任务生命周期

```mermaid
stateDiagram
    [*] --> 未初始化 : 系统启动
    
    未初始化 --> 已配置 : 用户点"应用配置"<br/>TaskConfigurator 初始化
    未初始化 --> 运行中 : 用户直接点"开始"<br/>Controller 自动初始化
    
    已配置 --> 运行中 : 用户点"开始"<br/>setTaskActive(true)
    
    运行中 --> 已暂停 : 用户点"暂停"<br/>taskActive=false
    已暂停 --> 运行中 : 用户点"继续"<br/>taskActive=true
    
    运行中 --> 已重置 : 用户点"重置"<br/>forceReset=true
    
    运行中 --> 已完成 : unexplored:set 为空<br/>探索率≥99.9%
    
    已重置 --> 已配置 : TaskConfigurator 重建
    已完成 --> [*] : 巡检完成
```

---

# 五、算法流程 Mermaid 图解

## 5.1 网格分区出生点算法（computeSpawnPoints）

```mermaid
graph TD
    Start["输入: mapWidth, mapHeight, carCount"] --> Check{"carCount ≤ 0?"}
    Check -->|"是"| ReturnEmpty["返回空列表"]
    Check -->|"否"| Calc["cols = ⌈√carCount⌉<br/>rows = ⌈carCount/cols⌉<br/>cellW = mapWidth/cols<br/>cellH = mapHeight/rows"]
    
    Calc --> Loop{"遍历网格<br/>row=0..rows-1<br/>col=0..cols-1<br/>idx < carCount"}
    
    Loop --> Center["cx = col×cellW + cellW/2<br/>cy = row×cellH + cellH/2"]
    Center --> Clamp["cx = clamp(cx, 1, mapWidth-2)<br/>cy = clamp(cy, 1, mapHeight-2)"]
    Clamp --> Add["spawns.add(Point(cx,cy))<br/>idx++"]
    Add --> Loop
    
    Loop -->|"完成"| Return["返回 spawns 列表"]

    style Start fill:#e8f5e9,stroke:#2e7d32
    style Calc fill:#e3f2fd,stroke:#1565c0
```

## 5.2 障碍物拒绝采样算法（generateObstacles）

```mermaid
graph TD
    Start["输入: w, h, density, forbidden"] --> Calc["targetCount = w×h×density<br/>maxAttempts = targetCount×20"]
    
    Calc --> Loop{"placed < targetCount<br/>AND<br/>attempts < maxAttempts?"}
    
    Loop -->|"是"| Random["x = random(0, w-1)<br/>y = random(0, h-1)"]
    Random --> Check{"不在禁区?<br/>AND 该位还没障碍物?"}
    Check -->|"是"| Accept["obstacles.add(p)<br/>placed++"]
    Check -->|"否"| Reject["拒绝, attempts++"]
    Accept --> Loop
    Reject --> Loop
    
    Loop -->|"否"| Batch["Pipeline 批量写入<br/>setBlockedBatch(obstacles)"]
    Batch --> End["返回 placed"]

    style Loop fill:#fff3e0,stroke:#f57c00
    style Check fill:#ffccbc,stroke:#e64a19
```

## 5.3 0-1 BFS 寻路算法

```mermaid
graph TD
    Start["输入: start, target,<br/>obstacles[][], explored[][], w, h"] --> Init["Deque q<br/>visited[][] = false<br/>parent map<br/>q.offerFirst(start)"]

    Init --> Loop{"q 非空?"}
    
    Loop -->|"是"| Poll["cur = q.pollFirst()"]
    Poll --> Goal{"cur == target?"}
    Goal -->|"是"| Reconstruct["回溯 parent<br/>构建路径 List"]
    
    Goal -->|"否"| Neighbor["遍历四方向邻居<br/>nx, ny"]
    Neighbor --> Check1{"在界内?<br/>未访问?<br/>不是障碍物?"}
    Check1 -->|"否"| Neighbor
    Check1 -->|"是"| Mark["visited= true<br/>parent 记录"]
    
    Mark --> Check2{"该格未探索?"}
    Check2 -->|"是 cost=0"| Head["q.offerFirst(neighbor)"]
    Check2 -->|"否 cost=1"| Tail["q.offerLast(neighbor)"]
    
    Head --> Neighbor
    Tail --> Neighbor
    Neighbor --> Loop
    
    Loop -->|"队列空"| Fail["返回 null<br/>路径不存在"]
    Reconstruct --> Return["返回路径"]

    style Check2 fill:#e8f5e9,stroke:#2e7d32
    style Head fill:#c8e6c9,stroke:#388e3c
    style Tail fill:#fff9c4,stroke:#f9a825
```

## 5.4 两步前瞻机制

```mermaid
graph TD
    Start["Car 收到 MOVE_STEP"] --> Peek1["peekNextStep()<br/>查看路径下一步"]

    Peek1 --> Check1{"路径为空?"}
    Check1 -->|"是"| Done["handleRouteDone()<br/>设 IDLE → ROUTE_NEEDED"]

    Check1 -->|"否"| Check2{"下一步有障碍?"}
    Check2 -->|"是"| Blocked["handleBlocked()<br/>设 BLOCKED → pushTask(BLOCKED)"]

    Check2 -->|"否"| Move["popNextStep()<br/>更新位置<br/>3×3 点亮<br/>步数+1"]

    Move --> Peek2["两步前瞻<br/>peek 第二步"]

    Peek2 --> Check3{"第二步有障碍?"}
    Check3 -->|"是"| Clear["清空剩余路径<br/>handleRouteDone()"]
    Check3 -->|"否"| Idle["设为 IDLE<br/>等待下一个 MOVE_STEP"]

    Move --> Broadcast["即时广播<br/>REFRESH_ALL"]

    style Check2 fill:#ffccbc,stroke:#e64a19
    style Check3 fill:#fff3e0,stroke:#f57c00
    style Move fill:#c8e6c9,stroke:#388e3c
```

---

# 六、数据结构 Mermaid 图解

## 6.1 Redis 黑板上的数据全景

```mermaid
erDiagram
    MAP_VIEW ||--|{ CAR_POSITION : "读取"
    MAP_BLOCKED ||--|{ CAR_POSITION : "障碍物+位置"
    
    CONFIG {
        string mapWidth
        string mapHeight
        string carCount
        string cars
        string taskActive
        string routeAlgorithm
    }
    
    CAR_STATUS {
        string value
    }
    
    CAR_POSITION {
        string x
        string y
    }
    
    CAR_TARGET {
        string json
    }
    
    CAR_ROUTE {
        string points
    }
    
    TASK_QUEUE {
        string tasks
    }
    
    UNEXPLORED {
        string coords
    }
    
    LOCKS {
        string value
    }
    
    PAUSES {
        string flag
    }
    
    SNAPSHOTS {
        string frames
    }
```

## 6.2 bitmap 坐标到偏移的映射

```mermaid
graph LR
    subgraph Map["40×40 地图 (0,0)~(39,39)"]
        direction TB
        G0["行0: 0 1 2 ... 39"]
        G1["行1: 40 41 42 ... 79"]
        GD["..."]
        G39["行39: 1560 ... 1599"]
    end

    subgraph Formula["偏移公式"]
        direction TB
        F["offset = y × 40 + x<br/>byteIdx = offset / 8<br/>bitPos = 7 - (offset % 8)<br/><br/>示例: (2,1) → offset=42<br/>→ byteIdx=5, bitPos=5"]
    end

    subgraph Storage["Redis BIT 存储"]
        direction TB
        B0["Byte 0: [b7 b6 b5 b4 b3 b2 b1 b0]<br/>offset:  0  1  2  3  4  5  6  7"]
        B1["Byte 1: [b7 b6 ...]<br/>offset:  8  9 ..."]
        B5["Byte 5: [b7 b6 b5 ...]<br/>offset:  40 41 42<br/>↑ (2,1) 的 bit"]
    end

    Map --> Formula --> Storage
```

## 6.3 taskQueue 的 LPOP/RPUSH 队列操作

```mermaid
graph LR
    subgraph Push["RPUSH 入队尾"]
        P1["pushTask(ROUTE_NEEDED,Car001)"]
        P2["pushTask(MOVE_READY,Car001)"]
        P3["pushTask(START)"]
    end

    subgraph Queue["Redis List: taskQueue"]
        direction LR
        Q0["队首<br/>LPOP→"] --- Q1["ROUTE_NEEDED<br/>Car001"] --- Q2["ROUTE_NEEDED<br/>Car002"] --- Q3["MOVE_READY<br/>Car001"] --- QN["队尾<br/>←RPUSH"]
    end

    subgraph Pop["LPOP/BLPOP 出队首"]
        PO1["Controller BLPOP 2s"]
        PO2["Controller 连续 LPOP"]
    end

    Push --> QN
    Q0 --> Pop
```

---

# 七、并发模型 Mermaid 图解

## 7.1 Controller 双线程架构

```mermaid
graph TB
    subgraph MainThread["main 线程"]
        M1["controller.start()"]
        M2["等待 JVM 退出"]
        M1 --> M2
    end

    subgraph TaskThread["taskProcessor 线程 (Daemon)"]
        direction TB
        T1["while(running)"]
        T2{"taskActive?"}
        T3["wait(1000)<br/>检查 Redis"]
        T4["BLPOP taskQueue 2s"]
        T5["processTask()"]
        T6["连续 LPOP 清空"]

        T1 --> T2
        T2 -->|"false"| T3
        T2 -->|"true"| T4
        T3 --> T1
        T4 --> T5
        T5 --> T6
        T6 --> T1
    end

    subgraph TickThread["broadcastScheduler 线程"]
        direction TB
        B1["每 100ms"]
        B2["探索完成判定"]
        B3["fallbackBlockedCheck()"]
        B4["tickDriveCars()"]
        B5["saveSnapshotIfRecording()"]
        B6["broadcastViewRefresh()"]

        B1 --> B2
        B2 --> B3
        B3 --> B4
        B4 --> B5
        B5 --> B6
    end

    subgraph Shared["volatile 共享变量"]
        direction LR
        V1["running"]
        V2["taskActive"]
        V3["recording"]
    end

    TaskThread -.->|"读写"| Shared
    TickThread -.->|"读写"| Shared

    style TaskThread fill:#e8f5e9,stroke:#2e7d32
    style TickThread fill:#e3f2fd,stroke:#1565c0
    style Shared fill:#ffccbc,stroke:#e64a19
```

## 7.2 分布式锁的获取与释放

```mermaid
sequenceDiagram
    participant C0 as Controller 实例0
    participant Redis as Redis
    participant C1 as Controller 实例1

    Note over C0,C1: 多实例同时检测到同辆车 BLOCKED

    C0->>Redis: SET lock:car:Car001 "thread123" NX EX 30
    Redis-->>C0: OK（获取成功）

    C1->>Redis: SET lock:car:Car001 "thread456" NX EX 30
    Redis-->>C1: nil（已被占用）

    C0->>Redis: clearCarRoute / clearCarTarget<br/>setCarStatus IDLE / pushTask
    C0->>Redis: Lua: if get==value then del
    Redis-->>C0: 锁已释放

    Note over C1: tryLock() 失败 → return（跳过，下次 tick 再试）
```

---

# 八、完整任务生命周期时序图

## 8.1 从启动到探索完成的完整消息序列

```mermaid
sequenceDiagram
    actor U as 👤 用户
    participant D as Display
    participant R as Redis
    participant C as Controller
    participant MQ as RabbitMQ
    participant TC as TaskConfigurator
    participant N as Navigator
    participant TP as TargetPlanner
    participant CA as Car Agent
    participant RP as Replay

    U->>D: 打开浏览器
    D->>R: 检查 config:task（为空）
    D->>MQ: 发 FORWARD_CONFIG(active=false)

    Note over D: === 用户点击"应用配置" ===

    MQ->>TC: FORWARD_CONFIG
    TC->>R: clearAll()
    TC->>R: initUnexploredSet(40,40)
    
    Note over TC: 网格分区出生点<br/>禁区构建<br/>障碍物生成(160个)
    
    TC->>R: setCarPosition/Status/Steps
    TC->>R: illuminateArea(出生点)
    TC->>R: setTaskConfig(...)
    TC->>R: pushTask(ROUTE_NEEDED,Car001~004)
    TC->>MQ: declareAllSystemQueues()
    TC->>R: BGSAVE

    Note over U: === 用户点击"开始" ===

    U->>D: 点"开始"
    D->>R: RPUSH START

    C->>R: BLPOP → START
    C->>R: setTaskActive(true)
    C->>R: BLPOP → ROUTE_NEEDED:Car001

    Note over C: === 第一轮：选目标 ===

    C->>MQ: NAVIGATE → Navigator4CarID
    MQ->>N: NAVIGATE(carId=Car001)
    N->>R: 读 position(10,10)
    N->>R: 读 target → null
    N->>MQ: GET_TARGET → TargetPlannerCmd
    MQ->>TP: GET_TARGET(carId=Car001)
    TP->>R: SRANDMEMBER unexplored:set
    TP->>R: 写入 car:Car001:target
    TP->>R: RPUSH ROUTE_NEEDED:Car001

    Note over C: === 第二轮：规划路径 ===

    C->>R: BLPOP → ROUTE_NEEDED:Car001
    C->>MQ: NAVIGATE → Navigator4CarID
    N->>R: 读 position + target + map:blocked
    N->>R: pushRoute(Car001, path)
    N->>R: setCarStatus(Car001, IDLE)
    N->>R: RPUSH MOVE_READY:Car001

    Note over C: === 第三轮：发移动命令 ===

    C->>R: BLPOP → MOVE_READY:Car001
    C->>C: 双层暂停检查 → 通过
    C->>MQ: MOVE_STEP → Car:Car001
    MQ->>CA: MOVE_STEP

    Note over CA: 两步前瞻 + 移动

    CA->>R: popNextStep() 弹出下一步
    CA->>R: 更新位置 + 3×3 点亮
    CA->>R: incrementCarSteps

    Note over C: === 100ms tick 广播 ===

    C->>MQ: REFRESH_ALL → Fanout UpdateView
    MQ->>D: Fanout 广播
    D-->>U: WebSocket 推送状态

    Note over C,RP: === tick 循环（重复直到完成）===

    C->>R: saveSnapshot(rpush replay:snapshots)

    Note over C: unexplored:set 为 0<br/>探索率 ≥ 99.9%<br/>🏁 完成！

    C->>R: setTaskActive(false)
    C->>MQ: REFRESH_ALL
    MQ->>D: completed=true
    D-->>U: 显示"探索完成"
```

---

# 附录：Mermaid 图表索引

| 编号 | 图表名称 | 类型 | 所属章节 |
|------|---------|------|---------|
| 1 | 整体架构总览 | graph TB | 2.1 |
| 2 | P3 职责边界图 | graph LR | 2.2 |
| 3 | 完整消息流转时序 | sequenceDiagram | 3.1 |
| 4 | Controller 任务分发 | graph TD | 3.2 |
| 5 | TaskConfigurator 三路分发 | graph TD | 3.3 |
| 6 | 小车三状态流转 | stateDiagram | 4.1 |
| 7 | taskActive 激活状态机 | stateDiagram | 4.2 |
| 8 | 小车+任务联合状态机 | stateDiagram | 4.3 |
| 9 | 完整任务生命周期 | stateDiagram | 4.4 |
| 10 | 网格分区出生点算法 | graph TD | 5.1 |
| 11 | 障碍物拒绝采样算法 | graph TD | 5.2 |
| 12 | 0-1 BFS 寻路算法 | graph TD | 5.3 |
| 13 | 两步前瞻机制 | graph TD | 5.4 |
| 14 | Redis 数据全景 ER 图 | erDiagram | 6.1 |
| 15 | bitmap 坐标映射 | graph LR | 6.2 |
| 16 | taskQueue 队列操作 | graph LR | 6.3 |
| 17 | Controller 双线程架构 | graph TB | 7.1 |
| 18 | 分布式锁获取释放 | sequenceDiagram | 7.2 |
| 19 | 完整任务生命周期时序 | sequenceDiagram | 8.1 |

---

*文档版本 v1.0 — 2026-06-23*
