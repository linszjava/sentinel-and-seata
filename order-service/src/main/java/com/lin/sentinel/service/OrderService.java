package com.lin.sentinel.service;

import com.lin.sentinel.entity.Order;
import org.apache.ibatis.annotations.Param;

/**
 *
 * @author linsz
 * @version v1.0
 * @date 2026/4/17 14:25
 */
public interface OrderService {

    void createOrder(@Param("order") Order order);

    void createOrderTcc(@Param("order") Order order);
}
