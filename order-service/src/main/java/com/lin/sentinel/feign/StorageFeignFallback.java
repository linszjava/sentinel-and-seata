package com.lin.sentinel.feign;

import org.springframework.stereotype.Component;

@Component
public class StorageFeignFallback implements StorageFeignClient {

    @Override
    public String deduct(Long productId, Integer count) {
        return "【Feign降级兜底】调用存储服务扣减库存失败，已被Sentinel熔断降级！商品ID：" + productId;
    }
}
