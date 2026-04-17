package com.lin.storage.controller;

import com.lin.storage.service.StorageService;
import jakarta.annotation.Resource;
import jakarta.websocket.server.PathParam;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/storage")
public class StorageController {

    @Resource
    private StorageService storageService;

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


    @PostMapping("/decrease")
    public String decrease(@RequestParam("productId") Long productId,
                            @RequestParam("count") Integer count) {

        storageService.decrease(productId, count);
        return "扣减成功 (From Storage Service, port: 8082)";
    }


}
