package com.lin.sentinel.service.impl;

import com.lin.sentinel.entity.Order;
import com.lin.sentinel.feign.StorageFeignClient;
import com.lin.sentinel.mapper.OrderMapper;
import com.lin.sentinel.service.OrderService;
import io.seata.spring.annotation.GlobalTransactional;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

/**
 *
 * @author linsz
 * @version v1.0
 * @date 2026/4/17 14:26
 */
@Service
public class OrderServiceImpl implements OrderService {

    @Resource
    private OrderMapper orderMapper;

    @Resource
    private StorageFeignClient storageFeignClient;

    @Override
    @GlobalTransactional(name = "create-order-tx",rollbackFor = Exception.class)
    public void createOrder(Order order) {
        orderMapper.insert(order);
        order.setStatus(0); // 创建中

        storageFeignClient.decrease(order.getProductId(), order.getCount());
        try {
            Thread.sleep(10000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        int i = 1 / 0;
        order.setStatus(1); // 已完成
        orderMapper.updateById(order);


    }

    @Override
    // 同理，发起端依旧只需一个 @GlobalTransactional 即可！具体是 AT 还是 TCC，交给下游的实现来决定。
    @GlobalTransactional(name = "create-order-tcc", rollbackFor = Exception.class)
    public void createOrderTcc(Order order) {
        orderMapper.insert(order);
        order.setStatus(0); // 创建中

        // 这里调用的是刚才新建的 TCC 预留接口！
        storageFeignClient.tccDecrease(order.getProductId(), order.getCount());

        // 为了方便测试回滚，留一个强行报错的雷
         int i = 1 / 0;
        
        order.setStatus(1); // 已完成
        orderMapper.updateById(order);
    }
}
