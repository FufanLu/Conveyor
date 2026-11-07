<p align="right"><a href="README.md">English</a> | <b>中文</b></p>

# Conveyor

**一个为 Prime Day 级流量设计的订单履约平台。** Conveyor 让每个订单像包裹在履约中心传送带上一样往前走：
下单 → 在合适的仓（FC）预占库存 → 支付授权 → 承诺送达日期 → 拣货出库 → 快递员的每一次扫描实时推回给用户。

技术上是 Java 25 + Spring Boot 4.1 的微服务。每个中间件都选的是能对应到 AWS 托管服务的那一款，
所以同一套设计既能在装了 Docker 的笔记本上跑，也能部署到 AWS。

> **当前进度：M7 已完成。** 在平台（M0）、商品目录（M1：两级缓存、Bloom 过滤器、OpenSearch、受保护的写操作）、
> 订单（M2：16 分片、幂等、outbox → Debezium → Kafka、CQRS 读模型）和库存（M3：Redis Lua 闸门、Postgres 事实、对账器、舱壁）
> 之上，每个下单都会跑一个 Temporal saga：预占 → 授权 → 确认库存 → 承诺送达日期 → 确认订单，每一步都有补偿，
> 支付等待窗口是一个 15 分钟的 durable timer。tracking-svc（M5）把快递员上报的洪峰写进 Kafka，
> 再落到 DynamoDB，并通过 SSE 推给浏览器。Web 前端（M6）把整个履约过程画成一张地铁线路图。M7 让系统在过载时有计划地降级：网关自适应准入、熔断器、带燃烧率告警的 SLO、穿过 outbox 的链路追踪，并在 CI 里用真实浏览器跑完整套系统。
> 见[路线图](#路线图)。

## 架构

```
           React + TS（商城 · 实时物流地图 · 运维大盘）
                               │ HTTPS / SSE
           gateway ── Spring Cloud Gateway ←→ Keycloak (OIDC/JWT)
           请求 ID · problem+json 错误 · 超时 · 限流 · 降载
                               │ REST（对外路由）   gRPC（内部）
  ┌─────────────┬──────────────┼──────────────┬────────────────┬──────────────┐
catalog-svc   order-svc      inventory-svc    fulfillment-svc  tracking-svc   payment-mock
OpenSearch    Postgres ×2    Redis 分桶       Temporal Saga    WebFlux        故障注入
Caffeine+     16 分片        + PG 台账        配送承诺          DynamoDB, SSE
Redis         + outbox       gRPC
  ▲               │ Debezium CDC                    │                ▲
  └── 读模型 ◄── Kafka (KRaft) + schema registry ◄────┴── simulator ───┘
```

数据归属和一致性取舍见 [docs/architecture.md](docs/architecture.md)（英文）。

## 技术栈

| 方面 | 选型 | 对应 AWS |
|---|---|---|
| 语言、框架 | Java 25（虚拟线程、record、sealed）、Spring Boot 4.1 | — |
| 构建 | Gradle 9 Kotlin DSL、convention 插件、version catalog、自动下载 JDK | — |
| 网关、认证 | Spring Cloud Gateway、Keycloak | API Gateway、Cognito |
| 服务调用 | 内部 gRPC + Protobuf，对外 REST + RFC 9457 错误格式 | — |
| 消息 | Kafka (KRaft)、Apicurio schema registry | MSK、Glue Schema Registry |
| CDC | Debezium、transactional outbox | MSK Connect、DMS |
| 工作流 | Temporal（订单 Saga、durable timer） | Step Functions、SWF |
| 关系库 | PostgreSQL ×2、16 个逻辑分片、显式路由（分片号编码在订单 ID 里） | Aurora PostgreSQL |
| KV | DynamoDB Local（单表设计） | DynamoDB |
| 缓存 | Redis + Caffeine 两级缓存 | ElastiCache |
| 搜索 | OpenSearch | OpenSearch Service |
| 容错 | Resilience4j、Netflix concurrency-limits | — |
| 可观测性 | Micrometer、Prometheus、Grafana、OpenTelemetry | CloudWatch、X-Ray |
| 交付 | Docker Compose、kind 上的 Helm、AWS CDK（Java） | EKS |

## 难点在哪

按里程碑列出：

- **热点 SKU 库存（M3，已实现）。** 秒杀开始那一刻，几千个下单请求同时打到同一个 SKU。在干净的 runner 上实测了四种方案：
  行锁和条件 UPDATE 卡在每秒 1.3–1.9k 件（一行就是一条队列），16 个分桶行能到每秒约 5k 件，Redis Lua 闸门加上无竞争的
  Postgres 插入能到每秒 6.5–8.1k 件，p99 在 6–90 ms。inventory-svc 采用最后这种，并配了对账器：修复稳定的漂移，
  Redis 重启后重建计数器。
- **下单 Saga（M4，已实现）。** 每个 `OrderPlaced` 启动一个 Temporal 工作流，工作流 id 就是订单 id，
  所以整个 topic 重放一遍也不会重复启动任何 saga。流程是预占 → 授权 → 确认库存 → 送达承诺 → 确认订单。
  拒付会释放库存；确认库存时 hold 已过期，就先作废支付、再释放库存。PENDING 的支付挂在 durable timer 上等回调，
  中途把 worker `kill -9` 掉，等待照样继续。
- **Outbox + CDC（M2，已实现）。** 订单、幂等键和事件在用户所在分片的同一个本地事务里提交；Debezium 从 WAL
  读出事件，以 Protobuf 字节原样发到 Kafka。CDC 宕机时下单不受影响。
- **CQRS（M2，已实现）。** 写请求走分片 Postgres；运营查询走 OpenSearch 投影，靠外部版本号丢弃重复事件。
- **两级缓存（M1）。** Caffeine + Redis，用 pub/sub 广播失效。single-flight 防击穿，Bloom filter 防穿透，
  TTL 加抖动防雪崩。
- **过载保护（M7）。** 网关上做令牌桶限流，再加上按延迟自适应调整的并发上限，过载时先丢低优先级流量。
- **物流事件洪峰（M5，已实现）。** 写入接口只往 Kafka 追加。persister 用条件写入 DynamoDB，
  重复或乱序的上报都不会让包裹状态倒退。每个实例有自己的 live 消费组，给 SSE 供数据：
  先发历史、再接实时，中间不漏不重，支持 `Last-Event-ID` 断点续传；运营地图按 500 ms 合并成一帧。
  在 4 vCPU 的 runner 上每秒 2 万条，660,000 条全部接收；"最新位置"写入合并后，shipment 写入次数降到原来的 1/4.4。

## 已经测出来的东西

仓库里的每个数字都来自实际运行。

**第 07 章（M7）。** 给一个每秒只能处理 400 个请求的上游压每秒 1,200 个请求：不做保护时所有请求都超时，有效吞吐降到 0；
开启网关的自适应准入后，有效吞吐稳定在每秒 390，99.8% 的订单成功、p99 为 125 ms，被丢弃的主要是搜索请求。准入限流的前两个版本在 runner 上失败过，
这两次失败都写进了文档。冻结 catalog-svc 后，订单服务的熔断器在 20 次慢调用后打开，订单从每次等 1 秒变成 17 ms 内直接失败。
真实浏览器走 Keycloak 登录、下单、看着 saga 和快递员走完全程；这个订单的链路穿过 outbox，跨了五个服务。
→ [docs/illustrated/07-when-everything-is-too-much.md](docs/illustrated/07-when-everything-is-too-much.md)（英文）

**第 06 章（M6）。** Web 前端把平台画成一张地铁线路图：
- 每个订单是一条线，saga 的每一步和快递员的每次扫描都是线上的一站。
- 站与站之间的长度就是两者之间真实经过的时间。
- 银行卡被拒时，线路在 45° 处分叉，终点是一个朱红色的方块。
- 快递员的轨迹画成从仓库换乘站出发的示意路线。
- 运营端看到的是一张网络示意图，外加六个仓库周边的放大图。

独立评审发现第一版的运营地图仍然是地理底图，快递员地图只是原始轨迹。之后改了两轮，还剩一项没有解决。
→ [docs/illustrated/06-the-web-as-a-transit-map.md](docs/illustrated/06-the-web-as-a-transit-map.md)（英文）

**第 05 章（M5）。** 在 4 vCPU 的 runner 上，每秒 2,000 到 20,000 条快递上报：写入接口每一档都全部接收，
实时推送的中位延迟只有几十毫秒。落库一度落后好几分钟，原因是一半的写入花在"最新位置"上，而这个值一秒后就会被覆盖。
改成每个包裹每 5 秒最多写一次之后，这类写入降到 1/4.4，落库吞吐提高到 2.4 倍。压测中途把 DynamoDB 冻结 20 秒，
快递端零报错，315,000 条上报全部恰好落库一次。
→ [docs/illustrated/05-the-courier-firehose.md](docs/illustrated/05-the-courier-firehose.md)（英文）

**第 04 章（M4）。** 四个订单走出 saga 的四种结局：确认、缺货拒绝、拒付（库存在通知用户之前就已释放）、
PENDING 后回调确认。在 150 秒的支付等待期间把 worker `kill -9`，Temporal 事件历史里那 151 秒是空白，
重启后 saga 从原地继续，最后确认。把消费组重置到最早位点重放全部 160 条事件：80 个 `OrderPlaced` 全部被识别为重复，
没有一个 saga 跑了两次。
→ [docs/illustrated/04-the-saga.md](docs/illustrated/04-the-saga.md)（英文）

**第 03 章（M3）。** 卖同一个 SKU：Redis Lua 闸门卖完 1 万件只要 1.2–1.5 秒，Postgres 行锁要 6.3–7.8 秒（24 轮，全部没有超卖）。
服务的第一版在一万人同时抢购时有 45% 的请求报错，还有库存卡在中间，买家却被告知"卖完了"。加了一道同时处理 64 个预占的
舱壁之后，同样的抢购恰好卖出 6000 件、拒绝 4000 件、零错误；这是在干净的 runner 上开关舱壁对照测出来的。
→ [docs/illustrated/03-ten-thousand-buyers-one-sku.md](docs/illustrated/03-ten-thousand-buyers-one-sku.md)（英文）

**第 02 章（M2）。** 订单、幂等键和 outbox 事件在同一个事务里提交；同一个 key 的 20 个并发重试只会生成 1 个订单。
第一次真实运行发现：Debezium 任务显示 RUNNING，10 秒后在第一次心跳时就挂了；投影把无害的重复事件送进了死信队列。
把 Debezium 的 poll interval 调小后，提交到进入 Kafka 的中位数从 289 ms 降到 33–50 ms。停掉 Debezium 期间订单照常接收，
它恢复流式读取后不到一秒，这些订单就出现在读模型里。
→ [docs/illustrated/02-placing-an-order.md](docs/illustrated/02-placing-an-order.md)（英文）

**第 01 章（M1）。** 1,000 个请求同时打到同一个冷 SKU，只查了 **1** 次数据库（普通 cache-aside 是 67–151 次，
不加缓存是 1,000 次）。1 万个请求查不存在的 SKU，只查了 **3** 次数据库（cache-aside 是 1 万次）；实测的 Bloom
误判率 0.020% 和公式算出来的完全一致。真实运行还发现了好几个测试没覆盖到的问题，比如 Redis 一次抖动就让服务起不来、
Keycloak 的 realm 导入把令牌里的角色弄丢了、另一个进程通过 IPv4 悄悄共用了 8080 端口。
→ [docs/illustrated/01-reading-a-product-under-a-stampede.md](docs/illustrated/01-reading-a-product-under-a-stampede.md)（英文）

**第 00 章（M0）。** 图解文档第 00 章跟着一个请求穿过网关，记录了第一次真实运行发现的问题：
`X-Request-Id` 头出现两次、错误响应里的 `requestId` 和响应头对不上、上游挂了却报 `500` 而不是 `503`。
这一章还实测了网关这一跳的代价（吞吐少约 9%，p99 多 0.9 ms），以及同步日志的代价：日志下游正常时几乎为零；
下游一旦卡住，网关就被拖到和下游一样慢（945 对 5,783 req/s）。
→ [docs/illustrated/00-a-request-through-the-edge.md](docs/illustrated/00-a-request-through-the-edge.md)（英文）

## 快速开始

需要 Docker，外加任意一个 JDK 17+ 用来运行 Gradle。JDK 25 由构建自动下载。

```bash
./gradlew build                       # 格式检查、javac -Werror、Error Prone、测试（含 Testcontainers）、覆盖率
make infra-up PROFILES="search auth events"  # 再加上 OpenSearch、Keycloak、Debezium connect、schema registry
make run-catalog-svc                  # 终端 1（第一次启动会灌入 10 万个商品）
make run-order-svc                    # 终端 2（会迁移两个订单库）
make run-inventory-svc                # 终端 3（gRPC 端口 9083）
make run-gateway                      # 终端 4
make connectors                       # 注册 Debezium outbox 连接器
curl -i localhost:8080/api/v1/products/B0DAA66D13
curl -s 'localhost:8080/api/v1/search?q=pour-over%20kettle&size=3'
```

Keycloak（仅用于开发）里有 realm `conveyor`，用户 `alice`/`alice`（customer）和 `ops`/`ops`（catalog-admin）；
`client_id=conveyor-cli` 允许脚本用密码模式拿令牌。宿主机端口如果被别的项目占了可以改，比如 compose 用
`CONVEYOR_OPENSEARCH_PORT=9210`，catalog-svc 用 `CONVEYOR_OPENSEARCH_URL=http://localhost:9210`。

## 目录结构

```
build-logic/          Gradle convention 插件（java、spring-service、spring-cloud、protobuf）
libs/platform/        请求 ID、访问日志、problem+json 错误、平台默认配置，以及每个服务都要跑的 ServiceContractTest
libs/proto/           gRPC 契约（库存预占协议）
services/             gateway、catalog-svc、order-svc、inventory-svc、fulfillment-svc、tracking-svc、payment-mock
deploy/compose/       本地中间件：镜像版本锁死、分 profile、设内存上限
tools/                压测工具和可复现的基准脚本
docs/                 架构文档和图解
```

## 路线图

| | 里程碑 | 状态 |
|---|---|---|
| M0 | 平台骨架：构建、服务、网关、契约测试、中间件、CI | 完成 |
| M1 | 商品目录 + 网关认证（Keycloak）+ 两级缓存 + OpenSearch | 完成 |
| M2 | 订单：分片 Postgres、outbox、Debezium → Kafka、幂等键、CQRS 读模型 | 完成 |
| M3 | 库存：多仓库存、Redis 闸门 + Postgres 事实、对账、舱壁、四种方案压测 | 完成 |
| M4 | 履约：Temporal Saga、配送承诺、支付故障注入 | 完成 |
| M5 | 物流追踪：快递事件洪峰、DynamoDB、SSE | 完成 |
| M6 | 前端：商城、实时地图、运维大盘 | 完成 |
| M7 | 韧性与可观测：降载、熔断、链路追踪、SLO 大盘、混沌实验 | 完成 |
| M8 | 压测与交付：Prime Day 场景、kind 上的 Helm、AWS CDK | |
