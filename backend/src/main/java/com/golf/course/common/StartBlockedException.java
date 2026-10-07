package com.golf.course.common;

import com.golf.course.dto.StartCheckView;

/**
 * 赛事开赛校验未通过：按最新球洞版本实时校验后存在阻断球洞 / 未配置洞 / 基础数据不完整。
 *
 * <p>与普通业务校验一样转 HTTP 400，但额外携带按最新状态重算的 {@link StartCheckView}
 * （受影响洞号、当前状态、修复提示、当前赛事版本），全局处理器把它放进响应体 {@code data}，
 * 前端无需再次请求即可直接渲染「被哪些球洞阻止」面板。</p>
 *
 * <p>抛出时开赛事务已回滚：赛事仍「报名中」、报名名单与成绩草稿原样保留、无半个进行中状态。</p>
 */
public class StartBlockedException extends BizException {

    private final transient StartCheckView view;

    public StartBlockedException(String message, StartCheckView view) {
        super(message);
        this.view = view;
    }

    public StartCheckView view() {
        return view;
    }
}
