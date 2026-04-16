# Sentinel 进阶实战与避坑指南：持久化与服务降级

本指南浓缩了我们在生产环境实战 Sentinel 时遇到的核心痛点、改造步骤，以及高阶的微服务熔断降级心法。

---

## 🎯 一、 Sentinel 规则持久化 (Nacos 推模式)

默认情况下，Sentinel Dashboard 对规则的修改只保存在各个微服务客户端的**内存**中。一旦微服务或者控制台重启，所有精心调优的规则将瞬间灰飞烟灭。因此，在生产环境中我们必须实现**持久化**。

### 1. 为什么选择 Nacos 推模式？
*   **推模式 (Push)**：控制台直接将修改后的规则推送到 Nacos 配置中心，微服务客户端通过监听 Nacos 来获取实时变更。
*   **优势**：Nacos 具有高可用特性，并且规则数据集中存储，是最受微服务架构欢迎的方案。

### 2. 源码改造核心步骤
由于官方控制台的 Nacos 推模式代码只在 `test` 测试目录下，我们需要手动克隆源码并进行修改后重新打包：
1.  **修改 `pom.xml`**：将 `sentinel-datasource-nacos` 依赖的 `<scope>test</scope>` 删掉。
2.  **代码迁移**：将 `test` 目录下的 `rule/nacos` 目录整体复制到 `main` 目录下。
3.  **配置文件设置**：在 `application.properties` 中添加 `nacos.server-addr=localhost:8848`。
4.  **前端路由对接**：修改 `sidebar.html`，将“流控规则”的超链接从 `dashboard.flowV1` 切换为真正的持久化页面 `dashboard.flow`。
5.  **重新编译打出新包**：`mvn clean package -DskipTests`。

### 3. ⚠️ 最大的避坑指南：V1 页面与 V2 页面陷阱
无数开发者在此折戟！
*   **旧的流控页面（V1版/内存版）**：界面右上角会有**“机器 IP 列表的下拉框”**。在里面点击添加规则，永远只会发向微服务内存，**绝对不会同步到 Nacos**！
*   **新的流控页面（V2版/持久化版）**：界面由于是针对整个微服务 App 生效的，右上角**没有机器 IP 列表下拉框**。必须在这个页面点击添加规则，Nacos 才会奇迹般地同步成功。
*   **注意浏览器缓存**：由于修改了 `sidebar.html`，必须使用**强制刷新**或**浏览器的无痕/隐身模式**登录，才能摆脱旧版页面的缓存。

### 4. 关于 Nacos 显示 TEXT 而不是 JSON 的问题
Sentinel 改造完发到 Nacos 后，Nacos 控制台显示的格式是 `TEXT`。这是完全正常的，并没有功能上的副作用。原因是 Sentinel 底层调用的早期版本 Nacos API 默认不携带类型标识，只要底层的字符串是标准 JSON 即可被微服务客户端正常反序列化加载。

---

## 🛡️ 二、 OpenFeign 整合 Sentinel 强力兜底策略

在真实的微服务环境中，请求大多数是互相调用的。比如 `order-service` 会通过 OpenFeign 去调用 `storage-service`。如果没有 Sentinel，一旦 `storage-service` 超时或者宕机，`order-service` 也会跟着被拖死。

### 1. 微服务环境准备
*   确保双方服务都注册到了 **Nacos Discovery** 服务列表中。
*   主调服务 (`order-service`) 必须引入了 `spring-cloud-starter-openfeign` 和 `spring-cloud-starter-loadbalancer`。

### 2. 核心配置开启
必须在 `order-service` 的 `application.yml` 里开启 OpenFeign 对 Sentinel 熔断器机制的接管：
```yaml
openfeign:
  circuitbreaker:
    enabled: true
```

### 3. 编写隔离与兜底逻辑 (Fallback)
当我们定义了一个用于远程调用的 FeignClient 时：
```java
@FeignClient(name = "storage-service", fallback = StorageFeignFallback.class)
public interface StorageFeignClient {
    @GetMapping("/storage/deduct")
    String deduct(@RequestParam("productId") Long productId, @RequestParam("count") Integer count);
}
```
我们只需要编写一个实现类，并加上 `@Component` 交给 Spring 容器：
```java
@Component
public class StorageFeignFallback implements StorageFeignClient {
    @Override
    public String deduct(Long productId, Integer count) {
        return "【Feign降级兜底】下游存储服务失联，Sentinel 自动保护为您返回兜底数据。";
    }
}
```
**实战效果**：当下游微服务彻底宕机报错时，`order-service` 中调用该服务的方法不会再抛出 500 服务器错误，而是像断路器一样立刻切断请求，并秒速返回以上友好提示，防止本方线程卡死。

---

## 🧠 三、 @SentinelResource 高级排雷（BlockHandler vs Fallback）

对于 `@SentinelResource` 注解的深度理解，区别在于它提供了两个非常重要的异常处理通道，绝对不能搞混：

### 1. `blockHandler` 专门用于捕捉 Sentinel 规则触发
*   **适用场景**：只处理因为触发了您在控制台配置的**流控、熔断、热点参数限制**而被 Sentinel 框架强行抛出的 `BlockException` 系列异常。
*   **方法要求**：方法签名必须跟原方法彻底一致，并且在参数列表**最后**额外加上一个 `BlockException`。
```java
public String handleBlock(Long productId, BlockException ex) {
    return "【限流阻挡】对不起，当前太拥挤了！";
}
```

### 2. `fallback` 专门用于捕捉原生代码业务错误
*   **适用场景**：处理您的业务代码自己抛出的异常，比如 `NullPointerException` 或者主动 `throw new IllegalArgumentException()`，它起到 try-catch 的作用，为您进行业务报错的兜底转换。
*   **方法要求**：方法签名也要跟原方法一致，最后可加上一个 `Throwable` 用于捕捉报错明细。
```java
public String handleFallback(Long productId, Throwable t) {
    return "【代码报错兜底】内部小失误，虽然报错了但是我为您接住了。";
}
```

### 3. 双剑合璧
如果同一个接口既配置了 `blockHandler` 也配置了 `fallback`：
*   如果是发生了系统限流（触发 Sentinel 策略），这归 `blockHandler` 管！
*   如果是发生了代码除以零（代码层错误），这归 `fallback` 管！
*   它们各司其职，保证了整个微服务接口的安全闭环。

---

## 🔥 四、 Sentinel 三大隐藏杀器：热点、授权与系统规则

除了最普遍的流控和熔断，Sentinel 还有三个极具威力的防御机制，它们在代码和配置上的体现截然不同。

### 1. 热点参数规则 (Hot Param Rule) —— “防刷单与爆款限流”
热点规则允许您**精确到参数的值**来进行限流。比如同样是查询商品，您可以规定：“普通商品每秒允许查 10 次，但商品ID为 `888` 的爆款，每秒允许查 100 次”。

*   **代码体现**：必须配合 `@SentinelResource` 注解，Sentinel 才能抓取到方法参数。
```java
@GetMapping("/hot")
@SentinelResource(value = "order-hot", blockHandler = "hotParamBlockHandler")
public String getProductHot(@RequestParam(value = "productId", required = false) Long productId) {
    return "商品详情查询成功！当前查询商品ID：" + productId;
}

public String hotParamBlockHandler(Long productId, BlockException ex) {
    if (ex instanceof ParamFlowException) {
        return "【热点限流启动】单品ID: " + productId + " 访问频次异常！";
    }
    return "系统繁忙";
}
```
*   **控制台实战玩法**：
    1. 在控制台【热点规则】中新增。资源名填 `order-hot`，参数索引填 `0`（代表第1个参数）。
    2. 设置基础单机阈值为 `1`。
    3. 点击【高级选项】，为参数值为 `888` 的特殊项配置阈值为 `100`。
    4. 此时狂刷 `productId=999` 会被迅速拦截，但狂刷 `productId=888` 将一路畅通！

### 2. 授权规则 (Authority Rule) —— “防非法网关与黑白名单”
防止黑客绕过大门直接通过 IP 请求内网机器，我们可以设置一个暗号。

*   **代码体现**：必须手写一个来源解析器 `RequestOriginParser` 交给 Spring 容器。
```java
@Component
public class CustomRequestOriginParser implements RequestOriginParser {
    @Override
    public String parseOrigin(HttpServletRequest request) {
        String origin = request.getHeader("origin");
        return (origin == null || origin.isEmpty()) ? "unknown" : origin;
    }
}
```
*   **控制台实战玩法**：
    1. 在控制台【授权规则】中新增。资源名填 `/order/hello`。
    2. 流控应用填 `unknown`。
    3. 模式选择 **黑名单**。
    4. 此时用浏览器直接访问接口将被拦截抛出 `AuthorityException`，除非您在 HTTP 请求头中带上非 `unknown` 的 `origin` 值！

### 3. 系统规则 (System Protection) —— “免代码的底层装甲”
当大兵压境，所有接口全都在涌入巨量并发时，再一条条配规则已经来不及了。系统规则借鉴 TCP BBR 算法，直接监控操作系统的 CPU、Load 和总入口 QPS。

*   **代码体现**：不需要向业务代码中写任何东西！它甚至不需要 `@SentinelResource` 注解。只要加上依赖，它就会全局生效。我们一般只会配一个全局异常处理器来让它显得优雅：
```java
@Component
public class GlobalSentinelExceptionHandler implements BlockExceptionHandler {
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, BlockException e) throws Exception {
        response.setStatus(429);
        response.setContentType("application/json;charset=utf-8");
        if (e instanceof SystemBlockException) {
            response.getWriter().write("【系统底层装甲生效】您的服务器遭遇海量流量，核心指标已超警戒线，系统已强制剥离多余请求以求生！");
        } else {
            response.getWriter().write("系统极度繁忙，请稍后再试");
        }
    }
}
```
*   **控制台实战玩法**：
    1. 在控制台【系统规则】中新增。无需填写资源名。
    2. 选择按【入口 QPS】或【CPU 使用率】等全局指标限制。比如入口 QPS 设置为 1。
    3. 此时只要全系统的入口 QPS 超过 1，整个 Spring Boot 应用的所有接口都将瞬间进入防御状态并触发上述的全局装甲拦截！
