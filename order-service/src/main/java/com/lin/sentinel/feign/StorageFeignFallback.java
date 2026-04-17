package com.lin.sentinel.feign;

import org.springframework.stereotype.Component;

@Component
public class StorageFeignFallback implements StorageFeignClient {

    @Override
    public String deduct(Long productId, Integer count) {
        return "【Feign降级兜底】调用存储服务扣减库存失败，已被Sentinel熔断降级！商品ID：" + productId;
    }

    @Override
    public String decrease(Long productId, Integer count) {
        // 🚨 分布式事务下的致命陷阱 🚨
        // ... 原来的注释 ...
        throw new RuntimeException("【Feign降级兜底】调用存储服务扣减库存失败或超时，触发 Seata 全局大回滚！商品ID：" + productId);
    }

    @Override
    public String tccDecrease(Long productId, Integer count) {
        throw new RuntimeException("【TCC降级兜底】尝试发起TCC预留库存时失联！触发总体回滚！商品ID：" + productId);
    }
}
