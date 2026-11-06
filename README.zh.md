<p align="right"><a href="README.md">English</a> | <b>中文</b></p>

# Conveyor

**一个为 Prime Day 级流量设计的订单履约平台。** Conveyor 让每个订单像包裹在履约中心传送带上一样往前走：
下单 → 在合适的仓（FC）预占库存 → 支付授权 → 承诺送达日期 → 拣货出库 → 快递员的每一次扫描实时推回给用户。

技术上是 Java 25 + Spring Boot 4.1 的微服务。每个中间件都选的是能对应到 AWS 托管服务的那一款，
所以同一套设计既能在装了 Docker 的笔记本上跑，也能部署到 AWS。

> **当前进度：M1 已完成。** 在 M0 平台（7 个服务、统一契约测试、网关）的基础上，商品目录现在用两级缓存加
> Bloom 过滤器提供 10 万个商品，在 OpenSearch 里做搜索；改价必须带 Keycloak 角色和匹配的 ETag。见[路线图](#路线图)。

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
| 关系库 | PostgreSQL ×2、ShardingSphere-JDBC（16 分片） | Aurora PostgreSQL |
| KV | DynamoDB Local（单表设计） | DynamoDB |
| 缓存 | Redis + Caffeine 两级缓存 | ElastiCache |
| 搜索 | OpenSearch | OpenSearch Service |
| 容错 | Resilience4j、Netflix concurrency-limits | — |
| 可观测性 | Micrometer、Prometheus、Grafana、OpenTelemetry | CloudWatch、X-Ray |
| 交付 | Docker Compose、kind 上的 Helm、AWS CDK（Java） | EKS |

## 难点在哪

按里程碑列出：

- **热点 SKU 库存（M3）。** 秒杀开始那一刻，几千个下单请求同时打到同一个 SKU。库存拆进多个 Redis 桶，
  用 Lua 原子预占，再和 Postgres 台账对账。四种方案（行锁、CAS、单 key Lua、分桶）放在一起实测对比。
- **下单 Saga（M4）。** 预占库存 → 支付授权 → 分配仓库 → 出库，任何一步失败都会补偿。
  未支付的订单由 Temporal 的 durable timer 自动取消，不用延迟队列。
- **Outbox + CDC（M2）。** 订单和它的事件在同一个本地事务里写入，Debezium 从 WAL 读出来发到 Kafka。消费端幂等。
- **CQRS（M2）。** 写请求走分片 Postgres，搜索和运营查询走 OpenSearch 读模型。
- **两级缓存（M1）。** Caffeine + Redis，用 pub/sub 广播失效。single-flight 防击穿，Bloom filter 防穿透，
  TTL 加抖动防雪崩。
- **过载保护（M7）。** 网关上做令牌桶限流，再加上按延迟自适应调整的并发上限，过载时先丢低优先级流量。
- **物流事件洪峰（M5）。** 模拟快递员上报 GPS，Kafka 按 shipment 分区，tracking-svc 写入 DynamoDB，
  再通过 SSE 推到浏览器地图上。

## 已经测出来的东西

仓库里的每个数字都来自实际运行。

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
make infra-up PROFILES="search auth"  # Postgres ×2、Redis、Kafka，加上 OpenSearch 和 Keycloak
make run-catalog-svc                  # 一个终端（第一次启动会灌入 10 万个商品）
make run-gateway                      # 另一个终端
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
| M2 | 订单：分片 Postgres、outbox、Debezium → Kafka、幂等键 | |
| M3 | 库存：多仓库存、分桶预占、对账、四种方案压测 | |
| M4 | 履约：Temporal Saga、配送承诺、支付故障注入 | |
| M5 | 物流追踪：快递事件洪峰、DynamoDB、SSE | |
| M6 | 前端：商城、实时地图、运维大盘 | |
| M7 | 韧性与可观测：降载、熔断、链路追踪、SLO 大盘、混沌实验 | |
| M8 | 压测与交付：Prime Day 场景、kind 上的 Helm、AWS CDK | |
