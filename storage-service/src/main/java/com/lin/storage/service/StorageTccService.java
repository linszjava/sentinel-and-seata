package com.lin.storage.service;

import io.seata.rm.tcc.api.BusinessActionContext;
import io.seata.rm.tcc.api.BusinessActionContextParameter;
import io.seata.rm.tcc.api.LocalTCC;
import io.seata.rm.tcc.api.TwoPhaseBusinessAction;

// 加上 @LocalTCC 注解，宣称它是一个 TCC 参与者
@LocalTCC
public interface StorageTccService {

    /**
     * TCC 的第一阶段：Try (尝试 / 资源预留)
     * TwoPhaseBusinessAction 负责告诉 Seata 这三个方法的对应关系：
     * name: TCC Action 名字，必须全局唯一
     * commitMethod: 确认方法名
     * rollbackMethod: 取消方法名
     */
    @TwoPhaseBusinessAction(name = "TccDecreaseStorage", commitMethod = "confirm", rollbackMethod = "cancel")
    void tryDecrease(
            @BusinessActionContextParameter(paramName = "productId") Long productId,
            @BusinessActionContextParameter(paramName = "count") Integer count
    );

    /**
     * TCC 的第二阶段：Confirm (确认执行)
     * 入参必须含有 BusinessActionContext，上下文里藏着 XID 和你第一阶段传过来的参数
     */
    boolean confirm(BusinessActionContext actionContext);

    /**
     * TCC 的第三阶段：Cancel (取消 / 回滚预留)
     */
    boolean cancel(BusinessActionContext actionContext);
}
