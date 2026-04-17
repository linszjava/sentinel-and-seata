package com.lin.storage.controller;

import com.lin.storage.service.StorageTccService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;

@RestController
public class StorageTccController {

    @Resource
    private StorageTccService storageTccService;

    @PostMapping("/tcc/decrease")
    public String decreaseTcc(@RequestParam("productId") Long productId,
                              @RequestParam("count") Integer count) {
        // TCC 模式下，远程调用只需要触发第一阶段 Try。
        // 因为 Try 阶段加了 @TwoPhaseBusinessAction，Seata TC 会在合适的时机，
        // 自动用 RPC/HTTP 回调这里的 confirm 或 cancel！
        storageTccService.tryDecrease(productId, count);
        return "TCC 尝试预留库存成功！(来自 TCC 改造接口)";
    }
}
