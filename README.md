# 简易商城 simple-mall（AI 智能导购）

在 [0802-3 微服务版商城](https://github.com/gangan666-a/simple-mall-microservices) 的基础上新增 **AI 智能导购**：下单后即可在订单详情页看到**同类商品推荐**；订单支付完成后，在支付成功页根据用户刚买的东西，用 DeepSeek 大模型推荐「**同类商品**」与「**可能需要的其他商品**（互补）」。

- **技术栈**：Spring Boot 3.5.16 · Spring MVC + Thymeleaf · Spring Data JPA · MySQL 8.4 · Java 25 · DeepSeek API
- **服务间通信**：REST（Spring `RestClient`），错误体统一 `{"message":"..."}`
- **数据库**：每服务独立 schema（微服务最佳实践），JPA `ddl-auto=update` 自动建表；推荐服务**无数据库**
- **AI 推荐**：`recommend-service`（:8084）调用 DeepSeek 大模型；无 API Key / 调用失败时自动回退到关键词互补规则推荐

## 架构

```
浏览器（唯一入口 http://localhost:8082）
        │
        ▼
┌─────────────────────┐    REST     ┌──────────────────────┐
│    order-service    │ ──────────▶ │    product-service   │  端口 8081
│   （订单服务 = BFF）  │ 商品/库存    │   shopdb_product     │
│   全部页面 + 订单API  │             │   product            │
│   shopdb_order      │             └──────────────────────┘
│   shop_order        │    REST
│   order_item        │ ──────────▶ ┌──────────────────────┐
└─────────────────────┘  发起支付     │    payment-service   │  端口 8083
        ▲                            │   shopdb_payment     │
        │                            │   payment            │
        │  支付确认回调（REST）        └──────────────────────┘
        │
        └──────────────▶ recommend-service（:8084，无数据库）
                             │   AI 智能导购：下单后推荐同类商品；
                             │   支付成功后推荐同类 + 互补商品
                             ▼
                        DeepSeek API（deepseek-v4-flash）
```

| 模块 | 类型 | 端口 | 数据库 |
| --- | --- | --- | --- |
| `mall-common` | 共享库（DTO/异常/枚举） | — | — |
| `product-service` | Spring Boot（纯 REST） | 8081 | `shopdb_product` |
| `order-service` | Spring Boot（BFF，全部页面 + REST） | 8082 | `shopdb_order` |
| `payment-service` | Spring Boot（纯 REST） | 8083 | `shopdb_payment` |
| `recommend-service` | Spring Boot（纯 REST，AI 智能导购） | 8084 | 无 |

## 如何运行

依赖软件（均已装好）：
- **JDK 25**：`D:\jdk25\jdk-25.0.2`
- **MySQL 8.4.5**：`D:\MySQL\mysql-8.4.5-winx64`（用户 `shop` / 密码 `shop123456`）

```bash
# 方式一：一键启动（启动 MySQL → 建库 → 打包 → 启动四个服务 → 打开浏览器）
双击 run.cmd

# 方式二：手动
D:\MySQL\start-mysql.cmd                                     # 先启动 MySQL
mysql -uroot -p < init.sql                                   # 首次：创建三个库并授权（或用 DBeaver 执行）
set JAVA_HOME=D:\jdk25\jdk-25.0.2
mvnw.cmd -DskipTests clean package                           # 打包（mall-common → 四个服务）
start "product"    java -jar product-service\target\product-service-0.0.1-SNAPSHOT.jar
start "order"      java -jar order-service\target\order-service-0.0.1-SNAPSHOT.jar
start "payment"    java -jar payment-service\target\payment-service-0.0.1-SNAPSHOT.jar
start "recommend"  java -jar recommend-service\target\recommend-service-0.0.1-SNAPSHOT.jar
```

启动后访问 **http://localhost:8082** （所有页面都从这里进）。

> 注意：本机默认 `JAVA_HOME` 是 JDK 1.8，编译/运行必须显式指定 JDK 25（run.cmd 已处理）。

## ✨ AI 智能导购

**流程**：`order-service` 把「已购商品 + 全量商品目录」发给 `recommend-service` → `recommend-service` 让 DeepSeek 大模型从目录中挑选商品并生成推荐理由 → 页面展示商品卡片。
两种模式（请求体 `type` 字段，缺省为 `complement`）：
- **`similar` 同类推荐**：挑选与已购商品**同品类**的升级款/竞品（以商品 `category` 字段为准）。下单后订单详情页、支付成功页都会展示「🔄 同类商品推荐」。
- **`complement` 互补推荐**：挑选互补/搭配商品。支付成功页展示「✨ AI 智能导购 · 你可能还需要」。

**模型选型：`deepseek-v4-flash`（关闭思考模式）**

| 考量 | 说明 |
| --- | --- |
| 为什么不是 `deepseek-v4-pro` | 推荐任务（从商品目录挑商品 + 一句话理由）不需要深度推理，flash 版速度更快、费用约 **1/3**（$0.14/M 输入、$0.28/M 输出） |
| 为什么关闭思考模式 | v4 系列默认开启 thinking，关闭后响应更快、`temperature` 才生效，且理由直接出现在 `content` 字段（而非 `reasoning_content`） |
| JSON 输出 | 使用 `response_format={"type":"json_object"}`，模型直接返回结构化的 `{"recommendations":[{productId, reason}]}`，服务端再做「目录内 / 非已购 / 去重」三层校验防止幻觉 |

**API Key 填写位置**（⭐）：

```
D:\work\0802\0802-4\recommend-service\src\main\resources\application.properties
```

把这一行补上 Key 即可（也可用环境变量 `DEEPSEEK_API_KEY` 覆盖，二选一）：

```properties
deepseek.api-key=sk-你的DeepSeek_API_Key
```

其他可调项（同一文件）：

```properties
deepseek.model=deepseek-v4-flash   # 想更强推理可改 deepseek-v4-pro
deepseek.top-n=4                   # 推荐条数上限
deepseek.max-tokens=2000           # 输出 token 上限（过小会截断 JSON）
deepseek.temperature=0.3           # 采样温度（0~2）
deepseek.timeout=12s               # 读取超时
```

**无 Key 也能跑**：不填 Key（或 DeepSeek 不可用 / 返回异常）时，`recommend-service` 自动回退到**规则推荐**——同类模式按商品 `category` 字段推荐同品类商品，互补模式按品类互补权重推荐（如：买了 iPhone → 推荐耳机 / Apple Watch），页面照常显示推荐，卡片角标从「🤖 AI 推荐」变成「同类优选」/「✨ 搭配推荐」。推荐服务整体不可用时，页面也**不会报错**，推荐区块自动隐藏。

## 页面与功能

| 路由 | 说明 |
| --- | --- |
| `/` 或 `/products` | 商品列表（31 个商品 · 15 个品类，数据来自商品服务） |
| `/product/{id}` | 商品详情（选数量 → 立即购买） |
| `/order/confirm?productId=&quantity=` | 确认订单（填写收货人/手机号/地址） |
| `POST /order` | 提交订单（调用商品服务扣减库存） |
| `/order/{orderNo}` | 订单详情（待支付可去支付 / 取消）+ **🔄 同类商品推荐** |
| `/order/{orderNo}/pay` | 模拟收银台：支付宝 / 微信支付 → **扫码支付**；银行卡 → **先选银行并绑定卡号，再输入支付密码** |
| `/order/{orderNo}/pay/success` | 支付成功页 + **🔄 同类商品推荐** + **✨ AI 智能导购（互补）** |
| `/orders` | 我的订单（可继续支付 / 取消） |

## REST API

**product-service（:8081）**
```
GET  /api/products                        # 商品列表
GET  /api/products/{id}                   # 商品详情
POST /api/products/{id}/stock/deduct?quantity=2   # 原子扣减库存（防并发超卖）
POST /api/products/{id}/stock/restore?quantity=2  # 回补库存
```

**order-service（:8082）**
```
GET  /api/orders/{orderNo}                # 订单详情（JSON，供支付服务）
POST /api/orders/{orderNo}/confirm-payment # 支付确认回调（置为已支付，幂等）
```

**payment-service（:8083）**
```
POST /api/payments   body: {"orderNo":"SO...","payMethod":"支付宝","payBank":"","payCardNo":""}
GET  /api/payments/{orderNo}              # 查询支付流水
```

**recommend-service（:8084）**
```
POST /api/recommendations
  body: {
    "type": "similar",                                  # "similar" 同类 / "complement" 互补（缺省互补）
    "purchasedItems": [{"productId":1,"productName":"iPhone 15 Pro","quantity":1}],
    "catalog":        [{"id":1,"name":"iPhone 15 Pro","category":"手机","price":7999.00,"imageUrl":"📱"}, ...]
  }
  resp: {
    "items": [{"productId":9,"productName":"华为 Mate 60 Pro","price":6999.00,
               "imageUrl":"📱","reason":"同为旗舰手机...","source":"ai"}, ...]
  }
```

## 关键设计

- **下单**：`OrderService.createOrder` 先调用商品服务**原子扣减库存**（`UPDATE ... WHERE stock >= ?`，并发安全），再本地生成订单 + 明细快照；本地保存失败时补偿回补库存。
- **库存正确性**：页面展示的库存只是提示，最终边界在商品服务的原子扣减。
- **支付**：`payment-service` 校验订单（待支付）→ 记录支付流水（仅存**卡号尾号 4 位**）→ 回调订单服务置为已支付；回调失败流水置 `FAILED`。
- **取消订单**：仅待支付订单可取消，并逐明细调用商品服务回补库存。
- **AI 导购容错**：`RecommendClient` 内部吞异常返回空列表，订单详情页 / 支付成功页用 `th:if` 隐藏空推荐区块——推荐服务挂了也绝不影响订单与支付展示。
- **商品品类**：`Product` / `ProductDTO` 带 `category` 字段（老数据为空），同类推荐以它为准、缺失时按商品名关键词兜底；种子数据按商品名 upsert，老库商品自动回填品类、不覆盖库存与价格。
- **金额**：全程 `BigDecimal`（12 位精度、2 位小数）。
- **状态枚举**：JSON 保持枚举 name（`CREATED/PAID/CANCELLED`），中文文案走 DTO 的 `statusLabel`。
- **错误处理**：页面异常渲染 `error.html`；REST 异常统一返回 `{"message":"..."}`；order-service 内 Web/REST 两套错误处理按包名隔离。

## 分布式事务限制（演示级）

跨服务无法保证强一致：下单「扣库存」与「落订单」分属不同数据库，若两者之间进程崩溃，可能出现已扣库存未回补；支付同理。生产环境需引入 **Saga / Outbox / 消息队列** 保证最终一致，本演示用「失败补偿 + 流水状态」做了最轻量的兜底。

## 目录结构

```
0802-4/
├── pom.xml                 # 父聚合 pom（四个服务）
├── init.sql                # 建库脚本（三个 schema + 授权）
├── run.cmd                 # 一键启动
├── mall-common/            # 共享库：DTO / BusinessException / OrderStatus
├── product-service/        # 商品服务（:8081）
├── order-service/          # 订单服务 + 全部页面（:8082）
├── payment-service/        # 支付服务（:8083）
└── recommend-service/      # AI 智能导购服务（:8084，无数据库）
```

> 本项目由 0802-3 微服务版扩展而来并新增 AI 导购；原单体代码保留在 0802-3 的 `simple-mall/` 目录作为参考。

## GitNexus 代码知识图谱

用 [GitNexus](https://github.com/abhigyanpatwari/GitNexus)（npm `gitnexus`，Java 核心支持）把本仓库索引成可查询的静态知识图谱（节点/调用边/跨服务 HTTP 契约），并带本地 Web UI。

```bash
# 安装（npm 11.x 必须全局装，勿用 npx）
GITNEXUS_SKIP_OPTIONAL_GRAMMARS=1 npm install -g gitnexus@latest

# 建索引（只写 .gitnexus/，不碰 AGENTS.md/CLAUDE.md）
cd D:\work\0802\0802-4
GITNEXUS_SKIP_OPTIONAL_GRAMMARS=1 GITNEXUS_LBUG_EXTENSION_INSTALL=load-only gitnexus analyze --index-only --skip-agents-md

# 起 Web UI 看图谱（默认 http://localhost:4747，测完即停）
gitnexus serve --port 4747
```

完整静态分析报告见 [`docs/trace/01-static-knowledge-graph.md`](docs/trace/01-static-knowledge-graph.md)。
