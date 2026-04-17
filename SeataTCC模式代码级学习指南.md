# Seata TCC 模式硬核学习指南 (基于当前项目源码)

为了让你在这套代码中能够最快、最深刻地掌握 TCC 模式的骨髓，我为你整理了这份“代码追踪动线”。请你完全按照以下的顺序，在 IDEA 中分别点开这些类进行阅读和打断点测试。

---

## 🧭 第一站：底层数据源探秘

**带着问题去看：为什么 TCC 这种“手动挡”必须更改数据库结构？**

1. **查看 `storage_db` 的表结构修改**
   - 关注 `t_storage` 表中多出的 `frozen`（冻结）字段。思考：为什么需要将可售库存和冻结库存切分开？
2. **查看那张神秘的新图表 `tcc_fence_log`**
   - 它的主键是 `xid` 和 `branch_id`。它是 Seata 官方为你准备用来自动处理“空回滚”、“悬挂”和“防同名重复调用（幂等）”的底层保障护城墙。

---

## 🏃 第二站：发起方司令部的集结 (Order Service)

**带着问题去看：大首领是如何发起 TCC 指令的？**

1. **打开 `OrderController.java`**
   - 看看第 112 行附近的 `@PostMapping("/seata/tcc/create/order")` 端点。
2. **重点打开 `OrderServiceImpl.java`**
   - 找到 `createOrderTcc` 方法。
   - 观察点：你会发现，哪怕在下游翻天覆地的变成了 TCC，**对于 Order 这个发号施令的长官而言，它依然只需要套上一个简单的 `@GlobalTransactional` 注解即可！** 这是因为 Seata 巧妙地把 TCC 的复杂度全部下放给了 RM（资源参与者）去承担。
3. **打开 `StorageFeignClient.java`**
   - 看看向库房发去的命令是如何变成 `/tcc/decrease` 这一新端点的。

---

## 🛠 第三站：TCC 三段论骨架剖析 (Storage Service 接口层)

**带着问题去看：如何告诉 Spring Boot 这是一个 TCC 组件而不是普通接口？**

1. **务必打开 `StorageTccService.java`**（最核心的契约文件）
   - **观摩注解一：`@LocalTCC`**。它加在接口上，告诉 Seata 的代理：“注意，这是一个纯种的 TCC 参与者，请给我特别的代理待遇！”。
   - **观摩注解二：`@TwoPhaseBusinessAction`**。它非常霸道地规定了第一阶段的方法（自己所在的 `tryDecrease`）、第二阶段确认方法（指向 `confirm`）、第二阶段取消方法（指向 `cancel`）。
   - **观摩参数注解：`@BusinessActionContextParameter`**。它的作用是把前端传来的“商品ID”和“扣减数量”，装进一个跨越三个阶段的黑色手提箱包（Context）里，确保 confirm 和 cancel 阶段依然能取出来接着用！

---

## 💥 第四站：实战兵工厂的血与火 (Storage Service 实现层)

**带着问题去读代码：如果一阶段的预付款已经交了，第二阶段系统忽然崩了，我是如何把预付款退回去的？**

直接打开 **`StorageTccServiceImpl.java`**，这是整个人类技术结晶体现的地方：
1. **阅读 `tryDecrease` 方法**
   - 搭配点击进入 `StorageMapper.java` 的 `updateTccTry` 方法查阅：可售库存 `residue` 减去了订单要求的量，而对应的数值立刻被赋予了 `frozen`。业务实质：**资源锁定！这批货谁也不能动！**
2. **阅读 `confirm` 方法**
   - 它没有传统形参了！它是从 `ActionContext` 里强行掏出第一步存进来的参数进行解析。
   - 搭配 `updateTccConfirm` 查阅 SQL：冻结清零，真正增加到“已消费（used）”里去。
   - **看防坑手段**：如果你看我的写 `if(updated == 0)` 的判断，这里就是为了应对“幂等”（万一 Seata 发配了两次重复的提交指令，不准扣两次）！
3. **阅读 `cancel` 方法**
   - 如果发生灾难，TC 会唤醒这个沉睡的代码。
   - 这段代码将执行 `updateTccCancel`，把原先放在 `frozen` 的数值，老老实实加回到可售 `residue` 里面供后续用户抢。
   - **看防坑手段**：前几行的 `if (productIdObj == null)` 是为了防范“空回滚”（第一步 try 压根就没运行，结果网崩了直接调了 cancel，此时连传参都没有，必须直接 return true 放行，不能傻乎乎去抛错！）。

---

## 💡 第五站：你的终极闭环实验

一切理论不跃然于 IDE 上都是纸上谈兵，请务必进行这终极两招测试：

**实验A：完美的完结尾声（测试 Confirm 逻辑）**
把代码里的 `int i = 1/0;` 这颗雷注释掉。发起 Postman 请求，通过 IDEA 断点捕捉，亲眼看着代码先进入 `tryDecrease` 跑完，再看着它自动无感切入 `confirm` 跑完！

**实验B：至暗时刻的力挽狂澜（测试 Cancel 逻辑）**
取消对雷的注释，还原 `int i = 1/0`。再次发起请求。这次你会在断点里看到截然相反的景象：代码进入 `tryDecrease` 跑完后，等了十秒钟，最终惨然失败，随后代码鬼魅般地切入了 `cancel` 的轨道，执行了最高权限的物理回滚！
