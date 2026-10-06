package com.golf.course.common;

/**
 * 并发冲突异常：操作所基于的版本已过期（他人已先提交/确认/裁决）。
 *
 * <p>统一转成 HTTP 409，消息中带当前最新状态，前端必须明确提示「数据已变化」
 * 并展示当前状态，禁止用过期页面静默覆盖另一人的结果。</p>
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
