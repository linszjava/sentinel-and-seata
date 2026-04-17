# Seata AT模式 完整代码与数据库SQL实战指南

在此文档中，为你提供使用 Seata (AT 模式) 需要完整的服务端数据库脚本、客户端 `undo_log` 脚本，以及经典的“订单模块调用库存模块”演示型完整 Java 代码。

---

## 0. 核心 POM 依赖 (`pom.xml`)
在进行一切代码和数据库实战之前，请确保你的 `order-service` 和 `storage-service` 项目的 `pom.xml` 包含了以下必备的微服务依赖库：

```xml
    <dependencies>
        <!-- Spring Boot Web -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>

        <!-- Spring Cloud Alibaba Nacos 注册中心 -->
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
        </dependency>

        <!-- Spring Cloud Alibaba Seata 核心依赖 (分布式事务) -->
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-seata</artifactId>
        </dependency>

        <!-- Spring Cloud OpenFeign (微服务远程调用) -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-openfeign</artifactId>
        </dependency>

        <!-- 负载均衡 LoadBalancer (Feign 必需组件) -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-loadbalancer</artifactId>
        </dependency>

        <!-- MyBatis-Plus (简化数据库操作 - Spring Boot 3 专属) -->
        <dependency>
            <groupId>com.baomidou</groupId>
            <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
            <version>3.5.5</version> <!-- Spring Boot 3 请务必使用 spring-boot3-starter -->
        </dependency>

        <!-- MySQL 驱动 -->
        <dependency>
            <groupId>mysql</groupId>
            <artifactId>mysql-connector-java</artifactId>
            <version>8.0.33</version>
        </dependency>

        <!-- Lombok (实体类简化 @Data 等) -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
    </dependencies>
```

---

## 1. 数据库 SQL 脚本

### (1) Seata Server 端所需表结构 (你修改了 `store.mode = db` 后这部分必不可少)
你需要单独建立一个用于 Seata 服务端管理的数据库，比如叫 `seata`，然后执行以下 SQL 生成其核心的三大表：(`global_table`, `branch_table`, `lock_table`)。

```sql
CREATE DATABASE IF NOT EXISTS `seata` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `seata`;

-- -------------------------------- The script used when storeMode is 'db' --------------------------------
-- the table to store GlobalSession data
CREATE TABLE IF NOT EXISTS `global_table`
(
    `xid`                       VARCHAR(128) NOT NULL,
    `transaction_id`            BIGINT,
    `status`                    TINYINT      NOT NULL,
    `application_id`            VARCHAR(128),
    `transaction_service_group` VARCHAR(128),
    `transaction_name`          VARCHAR(128),
    `timeout`                   INT,
    `begin_time`                BIGINT,
    `application_data`          VARCHAR(2000),
    `gmt_create`                DATETIME,
    `gmt_modified`              DATETIME,
    PRIMARY KEY (`xid`),
    KEY `idx_status_gmt_modified` (`status` , `gmt_modified`),
    KEY `idx_transaction_id` (`transaction_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- the table to store BranchSession data
CREATE TABLE IF NOT EXISTS `branch_table`
(
    `branch_id`         BIGINT       NOT NULL,
    `xid`               VARCHAR(128) NOT NULL,
    `transaction_id`    BIGINT,
    `resource_group_id` VARCHAR(32),
    `resource_id`       VARCHAR(256),
    `branch_type`       VARCHAR(8),
    `status`            TINYINT,
    `client_id`         VARCHAR(64),
    `application_data`  VARCHAR(2000),
    `gmt_create`        DATETIME(6),
    `gmt_modified`      DATETIME(6),
    PRIMARY KEY (`branch_id`),
    KEY `idx_xid` (`xid`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- the table to store lock data
CREATE TABLE IF NOT EXISTS `lock_table`
(
    `row_key`        VARCHAR(128) NOT NULL,
    `xid`            VARCHAR(128),
    `transaction_id` BIGINT,
    `branch_id`      BIGINT       NOT NULL,
    `resource_id`    VARCHAR(256),
    `table_name`     VARCHAR(32),
    `pk`             VARCHAR(36),
    `status`         TINYINT      NOT NULL DEFAULT '0' COMMENT '0:locked ,1:rollbacking',
    `gmt_create`     DATETIME,
    `gmt_modified`   DATETIME,
    PRIMARY KEY (`row_key`),
    KEY `idx_status` (`status`),
    KEY `idx_branch_id` (`branch_id`),
    KEY `idx_xid` (`xid`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;
```

### (2) 回滚日志表 `undo_log` 及 业务核心表 (供微服务使用)
在真实的微服务分布式架构中，订单服务和库存服务**必须拥有各自独立的数据库**！
而且，**每一个参与了分布式事务的数据库，都必须建一张配套的本地 `undo_log` 表**。

#### A. 订单微服务数据库 (`order_db`)
```sql
CREATE DATABASE IF NOT EXISTS `order_db` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `order_db`;

-- Seata AT 模式必备配套表：由订单微服务的 RM 管辖
CREATE TABLE IF NOT EXISTS `undo_log` (
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

-- 订单业务表
CREATE TABLE IF NOT EXISTS `t_order` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) DEFAULT NULL COMMENT '用户id',
  `product_id` bigint(20) DEFAULT NULL COMMENT '产品id',
  `count` int(11) DEFAULT NULL COMMENT '数量',
  `status` int(1) DEFAULT '0' COMMENT '订单状态：0:创建中; 1:已完结',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4;
```

#### B. 库存微服务数据库 (`storage_db`)
```sql
CREATE DATABASE IF NOT EXISTS `storage_db` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `storage_db`;

-- Seata AT 模式必备配套表：由库存微服务的 RM 管辖
CREATE TABLE IF NOT EXISTS `undo_log` (
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

-- 库存业务表
CREATE TABLE IF NOT EXISTS `t_storage` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `product_id` bigint(20) DEFAULT NULL COMMENT '产品id',
  `total` int(11) DEFAULT NULL COMMENT '总库存',
  `used` int(11) DEFAULT NULL COMMENT '已用库存',
  `residue` int(11) DEFAULT NULL COMMENT '剩余库存',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4;

-- 初始化一条库存数据用于测试
INSERT INTO `t_storage` (`product_id`, `total`, `used`, `residue`) VALUES (1, 100, 0, 100);
```

---

## 2. 核心 Java 业务代码实战 (100% 完整版)

以下是基于 Spring Boot + MyBatis-Plus + Spring Cloud Alibaba 的**全家桶级别完整代码**，你可以直接复制到对应的包下运行。

### (1) OrderService 订单微服务项目 (`order-service`)

#### A. 实体类: `Order.java`
```java
package com.lin.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("t_order")
public class Order {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long productId;
    private Integer count;
    private Integer status; // 0:创建中; 1:已完结
}
```

#### B. Mapper 接口: `OrderMapper.java`
```java
package com.lin.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lin.order.entity.Order;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrderMapper extends BaseMapper<Order> {
}
```

#### C. Feign 客户端: `StorageFeignClient.java`
```java
package com.lin.order.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(value = "storage-service")
public interface StorageFeignClient {

    @PostMapping("/storage/decrease")
    String decrease(@RequestParam("productId") Long productId, @RequestParam("count") Integer count);
}
```

#### D. Service 实现类: `OrderServiceImpl.java` (TM - 全局事务发起者)
```java
package com.lin.order.service.impl;

import com.lin.order.entity.Order;
import com.lin.order.mapper.OrderMapper;
import com.lin.order.feign.StorageFeignClient;
import io.seata.spring.annotation.GlobalTransactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class OrderServiceImpl {

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private StorageFeignClient storageFeignClient;

    /**
     * @GlobalTransactional: Seata 的核心注解，开启全局事务。
     * name: 全局事务名字，可以随意起。
     * rollbackFor: 任何异常都触发全局跨库回滚。
     */
    @GlobalTransactional(name = "create-order-tx", rollbackFor = Exception.class)
    public String createOrder(Order order) {
        System.out.println("-----> [订单微服务] 开始新建订单");
        // 1. 本地落库新建订单 (状态为 0-创建中)
        order.setStatus(0);
        orderMapper.insert(order);

        System.out.println("-----> [订单微服务] 开始调用远程库存微服务，做扣减");
        // 2. 远程调用库存微服务扣减库存 (XID 将通过 Feign 自动隐式传递)
        storageFeignClient.decrease(order.getProductId(), order.getCount());

        System.out.println("-----> [订单微服务] 扣减远程库存成功，修改本地订单状态为 1-完成");
        // 3. 将订单状态修改为完成
        order.setStatus(1);
        orderMapper.updateById(order);

        // 4. 👉👉👉 测试回滚大法：主动制造异常 👈👈👈
        // 只要下面这行报错，不管是当前订单库最新插入的订单，还是远程微服务扣减的库存，全部瞬间原路回滚！
        // int i = 10 / 0; 

        System.out.println("-----> [订单微服务] 下单全流程成功！");
        return "下单成功";
    }
}
```

#### E. Controller 控制器: `OrderController.java`
```java
package com.lin.order.controller;

import com.lin.order.entity.Order;
import com.lin.order.service.impl.OrderServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/order")
public class OrderController {

    @Autowired
    private OrderServiceImpl orderService;

    // 为了方便测试，直接使用 Get 请求暴露接口
    // 测试：http://localhost:8081/order/create?userId=1&productId=1&count=10
    @GetMapping("/create")
    public String create(Order order) {
        return orderService.createOrder(order);
    }
}
```

---

### (2) StorageService 库存微服务项目 (`storage-service`)

#### A. 实体类: `Storage.java`
```java
package com.lin.storage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("t_storage")
public class Storage {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long productId;
    private Integer total;
    private Integer used;
    private Integer residue;
}
```

#### B. Mapper 接口: `StorageMapper.java` (原生的自定义 SQL 扣减库存)
```java
package com.lin.storage.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lin.storage.entity.Storage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface StorageMapper extends BaseMapper<Storage> {
    
    // 自定义执行扣库存的 SQL，不需要写 xml 文件
    @Update("UPDATE t_storage SET used = used + #{count}, residue = residue - #{count} WHERE product_id = #{productId} AND residue >= #{count}")
    int decreaseStorageByProductId(@Param("productId") Long productId, @Param("count") Integer count);
}
```

#### C. Service 实现类: `StorageServiceImpl.java` (RM - 资源管理器执行者)
```java
package com.lin.storage.service.impl;

import com.lin.storage.mapper.StorageMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StorageServiceImpl {

    @Autowired
    private StorageMapper storageMapper;

    // 此处可加普通的本地 @Transactional 即可。
    // 它不需要加 @GlobalTransactional，它会自动参与到外层传递过来的 XID 全局分支中。
    @Transactional(rollbackFor = Exception.class)
    public void decrease(Long productId, Integer count) {
        System.out.println("-----> [库存微服务] 接收到远程调用，开始扣减数据库库存");
        
        int updated = storageMapper.decreaseStorageByProductId(productId, count);
        
        // 🚨 这里非常关键：如果更新的行数为 0，说明余额不足或者商品不存在！
        // 必须主动抛出 RuntimeException。这样才能触发 Seata 向全局 TM 发送回滚信号！
        if (updated == 0) {
            throw new RuntimeException("库存扣减失败！可能库存不足了！");
        }
        
        System.out.println("-----> [库存微服务] 本地库存扣减完毕");
    }
}
```

### (3) application.yml 最简核心配置例子
两个服务都需要如下类似的配置，核心是指明 `tx-service-group` 并能成功连接 Nacos 获取 Server。

```yaml
spring:
  application:
    name: order-service
  cloud:
    nacos:
      discovery:
        server-addr: 127.0.0.1:8848

# Seata 核心配置
seata:
  enabled: true
  application-id: ${spring.application.name}  # 应用本身的名字
  tx-service-group: default_tx_group          # 极为重要：事务群组。此值必须与 TC Server 配置绑定对应
  registry:
    type: nacos
    nacos:
      application: seata-server   # Seata TC 端在 Nacos 里注册的名字，默认通常写死了 seata-server
      server-addr: 127.0.0.1:8848
      group: SEATA_GROUP
```

有了这些内容，你就可以用完整的代码结合你刚修改过 `application.yml` `store.db` 的服务端跑通最初的 Seata 测试了！


