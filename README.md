# BlackBoxAI 智能巡检系统

多小车协同地图探索与巡检系统 — 基于 Redis 黑板架构 + RabbitMQ 消息总线的分布式 Java 应用。

## 环境要求

| 组件 | 版本 | 说明 |
|------|------|------|
| JDK | 17+ | `pom.xml` 中 `maven.compiler.source=17` |
| Maven | 3.6+ | Launcher 调用 `mvn.cmd` 编译和构建 classpath |
| Redis | 任意稳定版 | 默认 `localhost:6379`，存储共享状态（bitmap/set/hash） |
| RabbitMQ | 任意稳定版 | 默认 `localhost:5672`，账号 `guest/guest`，传递命令消息 |

> Windows 用户需确保 `mvn.cmd` 在 PATH 中（命令行执行 `mvn -v` 可输出版本号）。

## 快速启动（单机）

### 1. 启动基础设施

```bash
# 启动 Redis（任选一种方式）
redis-server.exe
# 或作为 Windows 服务：redis-server --service-start

# 启动 RabbitMQ（任选一种方式）
rabbitmq-server.bat
# 或作为 Windows 服务：net start RabbitMQ
```

### 2. 编译项目

```bash
mvn compile
```

### 3. 启动系统

**方式 A — 命令行一键启动**：

```bash
java -cp common\target\classes inspection.common.Launcher
```

**方式 B — IDEA 中运行**：

打开 `common/src/main/java/inspection/common/Launcher.java`，右键 → Run 'Launcher.main()'

### 4. 访问界面

启动成功后浏览器会自动打开 `http://localhost:8888`，若未自动打开请手动访问。

**默认登录账号**：`admin` / `admin123`

## 启动参数

Launcher 支持以下命令行参数（均为可选）：

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `--cars N` | 4 | 小车数量 |
| `--controller-instances N` | 1 | Controller 实例数 |
| `--display-http-port N` | 8888 | Display HTTP 端口 |

示例：启动 6 辆小车

```bash
java -cp common\target\classes inspection.common.Launcher --cars 6
```

## 端口占用

| 端口 | 用途 | 模块 |
|------|------|------|
| 6379 | Redis | 全部模块 |
| 5672 | RabbitMQ | 除 Auth/Replay 外的全部模块 |
| 8890 | 认证服务 HTTP | AuthServer |
| 8893 | 回放服务 HTTP | Replay |
| 8888 | 显示服务 HTTP | Display |
| 8887 | 显示服务 WebSocket | Display |

> Launcher 启动时会自动清理 8890/8893/8888/8887 端口的残留占用。

## 模块架构

项目包含 9 个 Maven 模块：

| 模块 | 职责 | 主类 |
|------|------|------|
| `common` | 公共基础设施（BlackboardClient/MessageBusClient/ConfigConstants/Launcher） | — |
| `auth` | 认证鉴权（admin 账号管理、登录校验） | `inspection.auth.AuthServerMain` |
| `task-configurator` | 任务初始化（地图生成、障碍物布置、小车放置） | `inspection.taskconfigurator.TaskConfiguratorMain` |
| `navigator` | 路径规划（BFS/A* 算法） | `inspection.navigator.NavigatorMain` |
| `target-planner` | 目标选择（多策略降级：NORMAL→CONSERVATIVE→EXTENDED→REMOTE→RESCUE） | `inspection.targetplanner.TargetPlannerMain` |
| `car` | 小车执行（移动、点亮周围区域） | `inspection.car.CarMain` |
| `controller` | 全局调度（Redis taskQueue 驱动 + 周期性广播） | `inspection.controller.ControllerMain` |
| `display` | 可视化（HTTP + WebSocket 实时推送） | `inspection.display.DisplayMain` |
| `replay` | 回放（快照录制/回放） | `inspection.replay.ReplayMain` |

## 工作流程

1. **TaskConfigurator** 生成地图、障碍物、放置小车到 Redis
2. **Controller** 从 Redis taskQueue 取任务，分发 NAVIGATE 请求到 Navigator
3. **TargetPlanner** 为每辆车分配目标点（多策略降级）
4. **Navigator** 规划路径（BFS/A*），写入 Redis，发 MOVE_READY
5. **Car** 执行移动，点亮周围未探索区域，更新 Redis bitmap
6. **Display** 通过 WebSocket 实时推送状态变化到浏览器
7. 循环 2-6 直到地图探索完成或所有小车永久阻塞

## 常见问题

### Q: 启动时报"无法连接 RabbitMQ"

RabbitMQ 服务未启动。请先执行 `rabbitmq-server.bat` 或 `net start RabbitMQ`。
依赖 RabbitMQ 的模块（TaskConfigurator/Navigator/TargetPlanner/Car/Display/Controller）会在构造时抛出 RuntimeException 导致启动失败。

### Q: 启动后 AuthServer 一直卡住

Redis 服务未启动。AuthServer 会无限重试初始化 admin 账号直到 Redis 可用。
请先执行 `redis-server.exe` 或 `redis-server --service-start`。

### Q: 编译失败提示"mvn 不是内部或外部命令"

Maven 未安装或不在 PATH 中。请安装 Maven 3.6+ 并确保 `mvn.cmd` 可在命令行执行。
IDEA 用户也可直接在 IDEA 中 Build Project，Launcher 会跳过编译失败继续启动。

### Q: 浏览器没有自动打开

Launcher 的浏览器自动打开可能被系统拦截，请手动访问 `http://localhost:8888`。

### Q: 端口被占用

Launcher 启动时会自动清理 8890/8893/8888/8887 端口的残留进程。
若仍报端口占用，请手动执行 `netstat -ano | findstr :8888` 查找占用进程并结束。

### Q: 如何修改 Redis/RabbitMQ 连接地址

各模块 Main 类支持以下参数（AuthServer 除外）：

```
--redis-host <host>     默认 localhost
--redis-port <port>     默认 6379
--mq-host <host>        默认 localhost
--mq-port <port>        默认 5672
```

## 技术栈

- **Java 17** + **Maven** 多模块项目
- **Redis**（Jedis 5.2.0）— 黑板架构共享状态
- **RabbitMQ**（amqp-client 5.22.0）— 命令消息传递
- **FastJSON2**（2.0.53）— JSON 序列化
- **SLF4J Simple**（2.0.16）— 日志
- **Java-WebSocket**（1.5.6）— Display 实时推送
- **com.sun.net.httpserver** — 轻量 HTTP 服务（JDK 内置）
