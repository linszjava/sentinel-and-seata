package com.lin.storage.mapper;

import com.lin.storage.entity.Storage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
* @author linsz
* @description 针对表【t_storage】的数据库操作Mapper
* @createDate 2026-04-17 13:50:54
* @Entity com.lin.storage.entity.Storage
*/
public interface StorageMapper extends BaseMapper<Storage> {

    int decreaseStorageByProductId(@Param("productId") Long productId, @Param("count") Integer count);

    // ================== TCC 核心 SQL ==================
    int updateTccTry(@Param("productId") Long productId, @Param("count") Integer count);

    int updateTccConfirm(@Param("productId") Long productId, @Param("count") Integer count);

    int updateTccCancel(@Param("productId") Long productId, @Param("count") Integer count);
}




