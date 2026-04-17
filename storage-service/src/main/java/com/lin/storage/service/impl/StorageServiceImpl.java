package com.lin.storage.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lin.storage.entity.Storage;
import com.lin.storage.service.StorageService;
import com.lin.storage.mapper.StorageMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
* @author linsz
* @description 针对表【t_storage】的数据库操作Service实现
* @createDate 2026-04-17 13:50:54
*/
@Service
public class StorageServiceImpl extends ServiceImpl<StorageMapper, Storage>
    implements StorageService{

    @Resource
    private StorageMapper storageMapper;

    /**
     * 扣减库存
     *
     * @param productId
     * @param count
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void decrease(Long productId, Integer count) {
        System.out.println("-----> [库存微服务] 接收到远程调用，开始扣减数据库库存");
        int updated = storageMapper.decreaseStorageByProductId(productId, count);
        
        // 🚨 如果更新的行数为 0，说明余额不足或者商品不存在！
        // 必须主动抛出 RuntimeException，触发 Seata 全局回滚信号
        if (updated == 0) {
            throw new RuntimeException("库存扣减失败！可能库存不足了！");
        }
        
        System.out.println("-----> [库存微服务] 本地库存扣减完毕");
    }
}




