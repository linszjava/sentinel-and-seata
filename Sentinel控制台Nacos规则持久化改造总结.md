# Sentinel 控制台 5 大规则 Nacos 持久化完全改造指南

## 1. 背景
默认情况下，Sentinel Dashboard（控制台）将规则推送到客户端（微服务应用），微服务只是保存在内存中。这种方式属于推送模式（Push）。一旦服务或者控制台重启，规则就会丢失。
为了做到生产可用的持久化，我们需要将 Sentinel 控制台改造为将规则推送到 **Nacos 配置中心**。微服务客户端再监听 Nacos 的变化。这样即便是重启，数据也不会丢失。

在此次改造中，我们涵盖了所有的 **5 大核心规则**：
1. 流控规则（FlowRule）
2. 降级规则（DegradeRule）
3. 系统规则（SystemRule）
4. 授权规则（AuthorityRule）
5. 热点参数规则（ParamFlowRule）

---

## 2. 深度改造前置：去除 Nacos 依赖的 Test 作用域
首先要在 Sentinel Dashboard 的 `pom.xml` 中将 Nacos 数据源的依赖作用域修改为运行时可用。
找到 `sentinel-datasource-nacos`，去掉 `<scope>test</scope>`。以便在打成业务 jar 包时包含该核心能力。

---

## 3. 核心大坑与解决：Entity 嵌套及冗余多余属性去除
在 Nacos 通信时需要使用 `Converter` 将对象转化为 JSON。Sentinel 控制台为了区分是哪个应用或机器添加的规则，自己包装了对应的 Entity 实体。
我们深入研究底层实体结构后，发现部分规则在直接被 FastJson 序列化时面临非常严重的**“嵌套问题”**或**臃肿问题**：

*   **扁平组：流控规则 (FlowRule)、降级规则 (DegradeRule)**
    *   **表现**：由于其属性都是平拍扁平的结构，即使携带了 `app` 等多余属性给 Nacos，客户端应用也能由于反序列化的容错成功解析需要的部分。
*   **重灾区组：授权规则 (AuthorityRule)、热点参数规则 (ParamFlowRule)**
    *   **表现**：`AuthorityRuleEntity` 和 `ParamFlowRuleEntity` 都继承了 `AbstractRuleEntity<T>`，它们将真实规则包装在了保护属性 `rule` 对象下。
    *   **后果**：直接 `JSON::toJSONString` 会生成类似 `[{"app":"...", "rule":{"resource":"...", ...}}]` 的结构，推送到 Nacos 后，微服务客户端按照原生对象的平级结构去反序列化时**发生丢失、完全无法解析**。
*   **微调组：系统规则 (SystemRule)**
    *   **表现**：虽然它像流控一样是扁平的，没有会导致解析失败的嵌套问题。但为了保持 Nacos 中的持久化 JSON 统一纯洁，依然建议剥离外壳。

**【终极解决方案】修改 `NacosConfig.java` 类里的 Encoder/Decoder**
我们重写了所有的转换器逻辑（尤其是系统、授权、热点这三种高级规则），在将其变成 JSON 推进 Nacos *之前*，提取内层的纯 Rule 对象；在从 Nacos 读取数据装载回 Dashboard *之后*，重新为其包装上 Entity 外壳。
```java
// 以 授权规则 (AuthorityRule) 为例，剥去外壳的核心转换器代码：
@Bean
public Converter<List<AuthorityRuleEntity>, String> authorityRuleEntityEncoder() {
    return entities -> {
        if (entities == null) { return "[]"; }
        // 只序列化内部的 AuthorityRule，不序列化 Entity 外壳，避免出现嵌套在 "rule" 字段中导致解析失败
        List<AuthorityRule> rules = entities.stream()
                .map(AuthorityRuleEntity::getRule)
                .collect(Collectors.toList());
        return JSON.toJSONString(rules);
    };
}
```

---

## 4. 建立 Nacos 发布者与提供者 (Publisher & Provider)
我们基于 `DynamicRulePublisher` 和 `DynamicRuleProvider` 接口为 5 大规则分别创造了如下推/拉实现类：
1. `FlowRuleNacosProvider` & `FlowRuleNacosPublisher`
2. `DegradeRuleNacosProvider` & `DegradeRuleNacosPublisher`
3. `SystemRuleNacosProvider` & `SystemRuleNacosPublisher`
4. `AuthorityRuleNacosProvider` & `AuthorityRuleNacosPublisher`
5. `ParamFlowRuleNacosProvider` & `ParamFlowRuleNacosPublisher`

以这些类为桥梁，当 Dashboard 控制台中点击修改时，Publisher 会把规则利用刚刚提到的转换器 Encoder 打包送进 Nacos。同样 Dashboard 打开列表刷新时，也会通过 Provider 从 Nacos 查询最新的 JSON 配置装回内存。

---

## 5. 重写 Controller 控制器逻辑
接下来需要在 `com.alibaba.csp.sentinel.dashboard.controller` 包下的各个控制器接入你的 Provider 和 Publisher。
- **FlowControllerV2.java**（这是专门针对推拉模式 v2 的端点）：释放、注册 `flowRuleNacosProvider`。
- **DegradeController.java**（目前 Sentinel 官方没有剥离 V2，所以要在原 Controller 里硬改）：将其基于内存写入读取的逻辑，统统替换为你自己写的 `degradeRuleNacosPublisher.publish(...)`。
- **SystemController.java**、**AuthorityRuleController.java**、**ParamFlowRuleController.java** 同理，需要全面接管其 `add`、`update`、`delete`、`apiQueryMachineRules` 等入口，接入 Nacos 发布者处理以取代本地内存。

---

## 6. 打包部署验收
当你成功修改了 `NacosConfig` 的嵌套修复，挂上 Provider Publisher，切断本地写逻辑换到 Nacos 后，进行最后一步：

在 `sentinel-dashboard` 的源码根目录执行命令重新打包成独立的 jar：
```bash
mvn clean package -DskipTests
```
将生成的 `target/sentinel-dashboard-x.x.x.jar` 启动。此时不管是增删改哪一种规则（流控、热点、授权等），它都能化作一份完美、干净、无嵌套的 JSON 持久化至 Nacos 配置列表里。服务即使重启，也会一秒同步回去！
