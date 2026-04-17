package com.lin.sentinel.feign;

import jakarta.websocket.server.PathParam;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "storage-service", fallback = StorageFeignFallback.class)
public interface StorageFeignClient {

    @GetMapping("/storage/deduct")
    String deduct(@RequestParam("productId") Long productId, @RequestParam("count") Integer count);

    @PostMapping("/decrease")
    public String decrease(@RequestParam("productId") Long productId,
                           @RequestParam("count") Integer count);

    @PostMapping("/tcc/decrease")
    String tccDecrease(@RequestParam("productId") Long productId, @RequestParam("count") Integer count);
}
