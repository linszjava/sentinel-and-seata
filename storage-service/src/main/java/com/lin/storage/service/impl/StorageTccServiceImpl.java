package com.lin.storage.service.impl;

import com.lin.storage.mapper.StorageMapper;
import com.lin.storage.service.StorageTccService;
import io.seata.rm.tcc.api.BusinessActionContextParameter;
import io.seata.rm.tcc.api.BusinessActionContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;

@Service
public class StorageTccServiceImpl implements StorageTccService {

    @Resource
    private StorageMapper storageMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void tryDecrease(
        @BusinessActionContextParameter(paramName = "productId") Long productId,
        @BusinessActionContextParameter(paramName = "count") Integer count) {
        System.out.println("==========> TCC Stage 1: Try 阶段开始");
        
        // 1. 获取本地锁，检查可售库存并“冻结”资源
        int updated = storageMapper.updateTccTry(productId, count);
        if (updated == 0) {
            throw new RuntimeException("TCC Try阶段失败：库存不足或商品不存在！");
        }
        
        System.out.println("==========> TCC Stage 1: Try 阶段成功，成功冻结库存 " + count + "件");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean confirm(BusinessActionContext actionContext) {
        System.out.println("==========> TCC Stage 2: Confirm 阶段开始，全局事务 XID: " + actionContext.getXid());
        
        // 提取 Try 阶段传递过来的参数，增加防御性空指针校验（针对修复前遗留的空参数事务）
        Object productIdObj = actionContext.getActionContext("productId");
        Object countObj = actionContext.getActionContext("count");
        if (productIdObj == null || countObj == null) {
            System.err.println("==========> TCC Stage 2: Confirm 发现参数为空，说明这是修改代码前遗留的失效重试请求，直接放行终止重试！");
            return true; 
        }

        Long productId = Long.valueOf(productIdObj.toString());
        Integer count = Integer.valueOf(countObj.toString());

        // 2. 扣减已冻结的库存，转化为真实消耗
        int updated = storageMapper.updateTccConfirm(productId, count);
        
        // 幂等性控制：如果 update 行数为 0，说明要么冻结量不足，要么已经执行过 confirm。
        // TCC 规范要求：Confirm 必须是幂等的，如果发现已被执行或找不到预期数据，可以返回 true 表示最终状态已达标。
        if (updated == 0) {
            System.out.println("==========> TCC Stage 2: Confirm 幂等判断：无需重复提交");
            return true;
        }

        System.out.println("==========> TCC Stage 2: Confirm 阶段成功结束！");
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean cancel(BusinessActionContext actionContext) {
        System.out.println("==========> TCC Stage 3: Cancel 阶段开始，全局事务 XID: " + actionContext.getXid());
        
        // 防御性校验
        Object productIdObj = actionContext.getActionContext("productId");
        Object countObj = actionContext.getActionContext("count");
        if (productIdObj == null || countObj == null) {
            System.err.println("==========> TCC Stage 3: Cancel 发现参数为空，放行终止死循环！");
            return true; 
        }

        Long productId = Long.valueOf(productIdObj.toString());
        Integer count = Integer.valueOf(countObj.toString());

        // 3. 将冻结的库存释放回可售库存
        int updated = storageMapper.updateTccCancel(productId, count);


        System.out.println("==========> TCC Stage 3: Cancel 阶段成功结束，库存释放原状！");
        return true;
    }
}
