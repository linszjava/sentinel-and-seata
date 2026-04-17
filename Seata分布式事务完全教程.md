# Seata 分布式事务完全解析与实战教程

## 1. 什么是 Seata？
Seata (Simple Extensible Autonomous Transaction Architecture) 是一款开源的分布式事务解决方案，致力于提供高性能和简单易用的分布式事务服务。在微服务架构下（如 Spring Cloud Alibaba），一个业务操作往往要跨越多个服务调用不同的数据库，这就需要分布式事务来保证“要么全部成功，要么全部失败”的强一致或最终一致性。

## 2. 核心概念组件 (非常重要)
Seata 的架构中有三大核心角色，分别分管不同的工作：

*   **TC (Transaction Coordinator) - 事务协调者**：
    *   **独立的服务端**（即你需要单独部署下载运行的 Seata-Server）。
    *   负责维护全局和分支事务的状态，驱动全局事务提交或回滚。
*   **TM (Transaction Manager) - 事务管理器**：
    *   **嵌入在客户端微服务中**（通常在发起全局事务的那个“入口微服务”中）。
    *   定义全局事务的范围：开始全局事务、提交或回滚全局事务。
*   **RM (Resource Manager) - 资源管理器**：
    *   **嵌入在客户端微服务中**（也就是负责操作具体自己本地数据库的每个微服务被调用方）。
    *   负责管理分支事务处理的资源，向 TC 注册分支事务和报告分支事务的状态，并驱动分支事务提交或回滚。

### 事务生命周期：
1. **TM** 向 **TC** 申请开启一个全局事务，全局事务创建成功并生成一个全局唯一的 `XID`。
2. **XID** 在微服务调用链路的上下文中传播 (比如在 HTTP/Feign 调用的 Header 里传递)。
3. **RM** 接收到 `XID` 后，向 **TC** 注册属于该 `XID` 的分支事务。
4. **TM** 根据业务代码执行的结果，向 **TC** 决定是发起全局提交还是全局回滚的指令。
5. **TC** 调度 `XID` 下管辖的所有分支事务完成提交或回滚请求。

---

## 3. 四大核心模式：AT、TCC、SAGA、XA
Seata 支持四种不同的分布式事务模式，适用于不同场景：

| 模式 | 理解难度 | 业务代码侵入性 | 性能 | 特点与适用场景 |
| :--- | :--- | :--- | :--- | :--- |
| **AT (默认主流)** | 极低 | 无 (只需一个注解) | 高 | 基于本地事务的两阶段提交，底层利用 `undo_log` 自动为你生成回滚反向 SQL。适用于主流关系型数据库。|
| **TCC** | 高 | 强 | 高 | 需要自己手动些 `Try`、`Confirm`、`Cancel` 三个方法。适用于非关系数据库（如 Redis, MongoDB），无锁化，支持自定义业务逻辑。|
| **SAGA** | 高 | 强 | 最高| 长事务解决方案，基于状态机和补偿方法。适用于耗时极长的金融/风控审批链路。|
| **XA** | 低 | 无 | 低 | 数据库原生强一致性协议。存在资源加锁阻塞，性能差但数据强一致，金融强一致可考虑。|

> **提示**：日常开发中 90% 以上情况你只需要掌握熟练使用 **AT 模式**。

---

## 4. AT 模式避坑核心原理 (撤销日志篇)
AT 模式如何做到帮我们“自动回滚”跨服务的分布式数据？**它靠的就是 `undo_log`（回滚日志表）**。

在 AT 模式两阶段提交机制中：
**第一阶段**：
RM 拦截你的业务 SQL，在更新你的数据*之前*，先对需要更新的记录查一遍存为前置快照（before image），执行业务 SQL 后，再查询一次记录存为后置快照（after image）。把这两份快照放入 `undo_log` 表中。然后 *真正提交* 包含业务和对 `undo_log` 日志表写入的本地事务。（此时锁释放，极大提升整体并发性能）。

**第二阶段**：
*   **如果 TM 决议全局提交**：TC 收到指令，通知各个 RM 异步清理这笔事务对应的 `undo_log` 日志记录，事务结束。
*   **如果 TM 决议全局回滚**：TC 通知各个 RM，RM 会根据记录的 `undo_log` 前置快照（before image）来生成反向补偿 SQL（如 `UPDATE` -> 修改回原样，`INSERT` -> 变为 `DELETE`），并执行。最后删除 `undo_log` 表记录。

👉 **结论**：你要使用 AT 模式，每一个参与分布式事务的**关系型数据库里，都必须新建一张 `undo_log` 表。**

---

## 5. Seata 实战部署与集成指南 (Spring Cloud Alibaba + Nacos 环境)

### 第一步：搭建与启动 Seata-Server (TC 服务端)
1. 去 Seata 官网或 GitHub 下载编译好的发行包 (如 v1.7.0 或以上)。
2. 配置 `seata/conf/application.yml`（或 `registry.conf` 旧版本）：
   - 将注册中心 (`registry`) 和 配置中心 (`config`) 指向你的 Nacos。
   - `store.mode` (存储模式) 设置为 `db` 以防止服务端宕机导致未完成的事务状态丢失，建立 `global_table` 等三张核心表。
3. 启动 `seata-server`，你可以在 Nacos 服务列表里看到它注册成功。

### 第二步：业务数据库准备 `undo_log` 表
在每个参与分布式事务的数据库运行如下建表语句：
```sql
CREATE TABLE `undo_log` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `branch_id` bigint(20) NOT NULL,
  `xid` varchar(100) NOT NULL,
  `context` varchar(128) NOT NULL,
  `rollback_info` longblob NOT NULL,
  `log_status` int(11) NOT NULL,
  `log_created` datetime NOT NULL,
  `log_modified` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `ux_undo_log` (`xid`,`branch_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8;
```

### 第三步：微服务客户端引入依赖
在订单模块、库存模块等 `pom.xml` 中引入启动器：
```xml
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-seata</artifactId>
</dependency>
```

### 第四步：编写 application.yml (客户端配置)
在你的微服务中添加如下关键配置：
```yaml
seata:
  enabled: true
  application-id: order-service
  tx-service-group: my-tx-group   # 事务群组名称(必填，需与 TC 上的 cluster_name 绑定关系一致)
  service:
    vgroup-mapping:
      my-tx-group: default        # Nacos 中 Seata 服务端通常使用 default 集群名
  registry:
    type: nacos
    nacos:
      application: seata-server
      server-addr: 127.0.0.1:8848
```

### 第五步：业务代码加上 `@GlobalTransactional` 神奇注解
这就大功告成了！现在，找到发起分布式调用的“大入口”方法（比如下单接口 `createOrder`），在这个方法上加上该注解：

```java
@Service
public class OrderServiceImpl implements OrderService {
    @Autowired
    private FeignStorageService storageService;

    // 核心注解：开启全局分布式事务，如果遇到异常则全局回滚
    @GlobalTransactional(name = "create-order-tx", rollbackFor = Exception.class)
    @Override
    public Order createOrder(Order order) {
        // 1. 本地落库订单
        orderMapper.insert(order);
        
        // 2. 跨微服务 HTTP 调用扣减库存 (XID 自动传递)
        storageService.decreaseStorage(order.getProductId(), order.getCount());
        
        // 3. 模拟异常发生！
        // int i = 1 / 0; 
        
        return order;
    }
}
```
**运作流程追踪：**
当代码走到 `1 / 0` 时引发必定抛除 Exception，TM 捕捉到该 Exception，迅速向 TC Nacos 上的 Server 发送“全局回滚”脉冲。TC 通知库存微服务和订单微服务，去检查各自数据库里的 `undo_log`。两个服务翻出老数据并生成相反 SQL 退回，所有数据完美还原！

---

## 6. Seata 进阶避坑指南 (重要)

### 坑一：关于 Feign XID 的传递丢失
如果你的多个微服务使用 OpenFeign 或者 RestTemplate 调用，Seata 会利用拦截器将上下文中存在的 `XID` 放进 HTTP Header 中（键名为 `TX_XID`）传递过去。如果遇到自定义拦截器覆盖或者异步多线程传递，`XID` 就会断裂导致事务失效。所以务必保证 **不要在全局事务中使用异步多线程调用其他微服务**。

### 坑二：脏读脏写引发的回滚失败
**非常致命**。AT 模式是基于 `undo_log` 的如果两阶段还没回滚，你的那行记录在这个空隙里**被非 Seata 的常规数据库修改语句更改了**，就会造成 `after_image` 对不上。此时为了安全，Seata **不再回滚** 该记录并抛出报错等待人工干预（所谓脏写）。
*   **解决：确保任何对于这笔关键数据的增删改，哪怕不在全局事务调用链里，也被 `@GlobalTransactional` 控制，或者如果只有一行本地 SQL 时，必须加上 `@GlobalLock` 注解抢占 Seata 的全局行锁，才能彻底避开。**

### 坑三：N+1 问题排查
由于每一笔 AT 操作都要写 `undo_log` 并提交，这意味着一次微服务业务原本执行 1 条 SQL，在 Seata 下背后变成了至少 3 条（前后镜像快照查+写log），耗时会轻微增加，这在高并发下需要被纳入考量范畴。遇到高并发绝对瓶颈时，应当更换为 TCC 模式或 RocketMQ 事务消息最终一致性。
