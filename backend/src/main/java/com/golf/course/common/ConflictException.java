package com.golf.course.common;

/**
 * 并发冲突异常：操作所基于的版本已过期（他人已先提交/确认/裁决/开赛/维护球洞）。
 *
 * <p>统一转成 HTTP 409，消息中带当前最新状态，前端必须明确提示「数据已变化」
 * 并展示当前状态，禁止用过期页面静默覆盖另一人的结果。</p>
 *
 * <p>{@link #data} 可携带当前最新状态的结构化视图（如开赛冲突时的最新赛事 + 球洞校验结果），
 * 由控制器放进响应体 {@code data}，前端拿到后直接渲染，无需再发一次查询。</p>
 */
public class ConflictException extends RuntimeException {

    private final transient Object data;

    public ConflictException(String message) {
        super(message);
        this.data = null;
    }

    public ConflictException(String message, Object data) {
        super(message);
        this.data = data;
    }

    public Object getData() {
        return data;
    }
}
