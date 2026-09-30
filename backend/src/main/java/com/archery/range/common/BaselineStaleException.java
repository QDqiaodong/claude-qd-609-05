package com.archery.range.common;

import java.util.List;

/**
 * 基线漂移异常：结清 / 登记前发现租借人、器材状态或会员余额基线变化时抛出。
 *
 * 服务方法以 noRollbackFor 声明本异常不触发回滚，使「清算单 → 待重新核对」的状态变更与轨迹
 * 在同一事务内提交（避免独立事务与已持有的行锁自锁）；异常仍透传到前端提示用户。
 */
public class BaselineStaleException extends BizException {

    private final transient List<String> drifts;

    public BaselineStaleException(String message, List<String> drifts) {
        super(message);
        this.drifts = List.copyOf(drifts);
    }

    public List<String> getDrifts() {
        return drifts;
    }
}
