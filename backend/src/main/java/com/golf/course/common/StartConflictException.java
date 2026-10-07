package com.golf.course.common;

import com.golf.course.dto.StartCheckView;

/**
 * 开赛操作过期 / 状态已变化：多人同时点「开始」、并发维护球洞后用旧页面提交、
 * 或赛事已被推进到「进行中 / 已结束」。
 *
 * <p>统一转 HTTP 409，并携带按最新赛事与球洞状态重算的 {@link StartCheckView}，
 * 前端据此提示「数据已变化」并直接展示当前状态，禁止用过期页面再做一次写。</p>
 */
public class StartConflictException extends ConflictException {

    private final transient StartCheckView view;

    public StartConflictException(String message, StartCheckView view) {
        super(message);
        this.view = view;
    }

    public StartCheckView view() {
        return view;
    }
}
