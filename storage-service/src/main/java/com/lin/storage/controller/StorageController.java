package com.lin.storage.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/storage")
public class StorageController {

    @GetMapping("/deduct")
    public String deduct(@RequestParam("productId") Long productId, @RequestParam("count") Integer count) {
        // 模拟一个随机的慢调用或错误，方便后续测试熔断降级
        if (productId == -1L) {
            throw new RuntimeException("模拟异常：扣减库存失败");
        }
        
        if (productId == -2L) {
            try {
                Thread.sleep(3000); // 模拟耗时，触发慢调用比例熔断
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        
        System.out.println("扣减商品库存成功, productId=" + productId + ", count=" + count);
        return "扣减成功 (From Storage Service, port: 8082)";
    }
}
