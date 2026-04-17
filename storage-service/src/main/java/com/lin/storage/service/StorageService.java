package com.lin.storage.service;

import com.lin.storage.entity.Storage;
import com.baomidou.mybatisplus.extension.service.IService;

/**
* @author linsz
* @description 针对表【t_storage】的数据库操作Service
* @createDate 2026-04-17 13:50:54
*/
public interface StorageService extends IService<Storage> {

    /**
     * 扣减库存
     * @param productId
     * @param count
     * @return
     */
    void decrease(Long productId, Integer count);

}
