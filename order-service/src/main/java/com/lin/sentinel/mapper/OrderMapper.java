package com.lin.sentinel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lin.sentinel.entity.Order;
import org.apache.ibatis.annotations.Mapper;

/**
 *
 * @author linsz
 * @version v1.0
 * @date 2026/4/17 13:28
 */
@Mapper
public interface OrderMapper extends BaseMapper<Order> {
}
