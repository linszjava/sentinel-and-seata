package com.lin.sentinel.controller;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.lin.sentinel.feign.StorageFeignClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/order")
public class OrderController {

    /**
     * 1. 基础流控测试
     * 不需要加 @SentinelResource 也可以被 Sentinel 捕获（因为引入了 web starter，所有的 url 默认就是资源）
     * 资源名为接口路径：/order/hello
     */
    @GetMapping("/hello")
    public String hello() {
        UUID uuid = UUID.randomUUID();
        return "Hello, Sentinel" +uuid;
    }

    @Autowired
    private StorageFeignClient storageFeignClient;

    /**
     * 2. 高阶实战：OpenFeign 调用 + 降级处理 + 原生异常区分
     * 使用 @SentinelResource 同时指定限流异常（blockHandler）与业务报错（fallback）的分离处理。
     */
    @GetMapping("/create")
    @SentinelResource(value = "order-create", blockHandler = "handleBlock", fallback = "handleFallback")
    public String createOrder(@RequestParam("productId") Long productId) {
        // 第一步：这可能是您自己本地代码突然空指针等业务报错
        if (productId == 0L) {
            throw new IllegalArgumentException("商品ID不能为0（本地参数校验抛出）");
        }

        // 第二步：通过 OpenFeign 远程调用库存服务扣减库存
        // 传递 count=1。如果我们传 productId=-1，下游服务会主动报错；传 productId=-2，下游服务会模拟卡顿超时（慢调用）。
        String storageResult = storageFeignClient.deduct(productId, 1);

        return "订单创建成功！下游情况：" + storageResult;
    }

    // blockHandler：【专门处理】Sentinel 熔断器或是流控规则抛出的异常。它能精准拦截比如系统 QPS 超标等。
    public String handleBlock(Long productId, BlockException ex) {
        return "【Block被触发】抱歉，当前系统极度繁忙，您对商品 " + productId + " 的抢购已被限流！(原因: " + ex.getClass().getSimpleName() + ")";
    }

    // fallback：【专门处理】除了 BlockException 之外的运行时报错（如业务逻辑自己抛的各种 Exception）。
    // 当这两种同时配了以后，各自司职！
    public String handleFallback(Long productId, Throwable t) {
        return "【Fallback被触发】哎呀，购买商品 " + productId + " 时内部系统出了点小差错（原因：" + t.getMessage() + "），但为您兜底了，没把红色的500报错抛出去。";
    }

    /**
     * 3. 终极防御：热点参数限流测试
     */
    @GetMapping("/hot")
    @SentinelResource(value = "order-hot", blockHandler = "hotParamBlockHandler")
    public String getProductHot(@RequestParam(value = "productId", required = false) Long productId) {
        return "商品详情查询成功！当前查询商品ID：" + productId;
    }

    // 专属的热点参数限流兜底方法
    public String hotParamBlockHandler(Long productId, BlockException ex) {
        if (ex instanceof com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowException) {
            return "【热点限流启动】警告！检测到针对单品ID: " + productId + " 的海量并发访问，已被拦截隔离保护！";
        }
        return "系统繁忙，请稍后再试";
    }

    /**
     * 3. 熔断降级测试
     * fallback 用于处理业务异常（包括熔断降级）。
     */
    @GetMapping("/query/{id}")
    @SentinelResource(value = "order-query", fallback = "queryFallback")
    public String queryOrder(@PathVariable("id") Integer id) {
        if (id < 0) {
            throw new RuntimeException("订单ID不合法");
        }
        return "订单查询成功: " + id;
    }

    // fallback 方法，如果上面的方法抛出异常或被降级，会进入这里兜底
    public String queryFallback(Integer id, Throwable t) {
        return "订单查询降级返回: 请求失败，原因: " + t.getMessage();
    }
}
