# Seata 进阶知识点与防坑指南：脏读脏写与全局锁 (@GlobalLock)

打通了 AT 模式之后，你已经掌握了 Seata 在企业中最核心、使用频率最高的武器。但在应对更加极端的高并发、数据一致性要求极高的场景时，还需要深入了解以下“深水区”知识。

---

## 1. 核心大坑：脏写与脏读的危机

在 Seata 的 AT 模式下，本地事务（RM）执行完 SQL 后会立刻提交，释放 MySQL 底层的本地行锁。但此时，整个事务的“全局锁”依然在 Seata大本营（TC）手中保留，等待所有其他服务执行完毕后决定最终是统一 `Commit` 还是统一 `Rollback`。

在这个“本地已提交，全局未决断”的几百毫秒时间差内，就出现了极其危险的空窗期。

### 危机 A：脏读
如果此时有其他查询接口去数据库读取该行记录，由于本地事务已提交，它会直接读到那个尚未被全局确认的处于“中间态”的数据。如果是涉及财务和余额对账系统，这种假数据是非常致命的。

### 危机 B：脏写（回滚瘫痪的元凶）
如果在空窗期内，有一个**不受 Seata `@GlobalTransactional` 管控的普通写代码**（或者管理员在后台手动执行了 UPDATE）去修改了同一行数据，因为没有本地锁，更新会顺畅完成。
**灾难发生**：当全局事务报错要求回滚时，Seata 根据 `undo_log` 中的后置镜像（After Image）去校验数据库里的当前记录，结果发现数据被人动过了，它会立刻抛出异常、拒绝回滚，导致该全局分布式流水变为死账！

---

## 2. 破局利器：如何优雅地防范脏读与脏写？

想要解决以上问题，只需要请出大杀器：`@GlobalLock`。该注解负责约束那些本身不需要开启庞大全局事务，但又必须保证与全局事务操作同一条数据是不发生冲突的普通代码。

### 实战一：防范脏写冲突的正确姿势

只要在那些“有可能趁虚而入”去修改库存的普通 Service 接口上，加一个 `@GlobalLock` 配合普通的本地事务即可。

```java
import io.seata.spring.annotation.GlobalLock;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

@Service
public class AdminStorageService {

    @Autowired
    private StorageMapper storageMapper;

    // 重点：不开启代价高昂的全局大事务，但通过 @GlobalLock 守护这行数据
    @GlobalLock
    @Transactional(rollbackFor = Exception.class)
    public void resetStorage(Long productId, Integer newStock) {
        // 当执行对应的原生 update 语句时，
        // Seata 拦截器会强制去 TC 检查该行记录顶上是否有其他人在使用的“全局锁”
        // 如果有，这个 update 不会执行，而是原地排队挂起等待
        storageMapper.updateStock(productId, newStock);
    }
}
```

### 实战二：防范脏读越界的最高级写法规

如果不光要防修改，还要防止别的代码过早“看”到未完成交易的数据，就需要修改 Mapper 查询方法，配合 `@GlobalLock` 进行联防。

**第一步：写一条带排他锁的查询 SQL (`FOR UPDATE`)**
```java
public interface StorageMapper extends BaseMapper<Storage> {
    
    // 注意末尾必须要加上 FOR UPDATE 锁标识
    @Select("SELECT * FROM t_storage WHERE product_id = #{productId} FOR UPDATE")
    Storage getStorageByIdForUpdate(@Param("productId") Long productId);
}
```

**第二步：在 Service 服务层套盾**
```java
    @GlobalLock
    @Transactional(rollbackFor = Exception.class)
    public Storage strictQuery(Long productId) {
        // 如果只是普通查询，甚至不管 MySQL 锁如何，查出来大概率就是脏数据。
        // 但由于这是一个加入了 FOR UPDATE 意向的 SQL 且被 @GlobalLock 监听，
        // Seata 代理层不会去真枪实弹地抢占 MySQL锁，而是拿着凭证去大本营检查是否被全局锁封锁！
        // 如果被封锁，查询直接卡住，从而彻底杜绝查到脏数据。
        return storageMapper.getStorageByIdForUpdate(productId);
    }
```

### 【知识总结金句】
*微服务只要涉及到写库，务必加稳 Seata 依赖！如果某段代码虽然不参与跨国界大事务，但它动了同一片蛋糕：想要防修改冲突（防脏写），加 `@GlobalLock`；想要防看穿底牌（防脏读），就用 `SELECT ... FOR UPDATE` 再配合 `@GlobalLock`！*


---

## 3. 架构师进阶：关于分布式事务的其他思考

1. **所有的非关系型数据库怎么搞？**
   如果微服务扣减的不是 MySQL 里的库存，而是 **Redis** 或者调用的第三方平台 API？AT 模式将彻底不起作用，你需要深入自研 **TCC（Try-Confirm-Cancel）模式**。

2. **如果并发量真的太高，高到每秒几千怎么搞？**
   像“双十一秒杀”这种抢手商品的场景，一旦用上 Seata，全局锁会像堵车的十字路口导致性能直线雪崩。此时务必要放弃 Seata（强一致性），转投 RocketMQ 事务消息 或者 RabbitMQ + 本地消息记录表 来确保 **最终一致性**！这也恰恰证明了在这个行业里没有银弹，一切都要做性能和准确度上的平衡。
