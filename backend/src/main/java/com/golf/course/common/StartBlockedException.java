package com.golf.course.common;

/**
 * 开赛校验未通过：绑定球场的应打球洞中存在维护 / 封闭 / 未配置 / 基础数据不完整的洞。
 *
 * <p>与普通业务校验一样转 HTTP 400；区别是携带结构化的 {@link com.golf.course.dto.StartCheckView}
 * （逐洞洞号 / 当前状态 / 修复提示 + 最新赛事与球洞版本），控制器把它放进响应体 data，
 * 前端可以直接渲染「被哪些球洞阻止」面板，不需要再发一次查询。
 *
 * <p>抛出时开赛事务已回滚：赛事仍为「报名中」，报名名单与成绩草稿都未被清理，
 * 也不会写入冻结球洞清单（不留半个进行中状态）。</p>
 */
public class StartBlockedException extends BizException {

    private final transient com.golf.course.dto.StartCheckView checkView;

    public StartBlockedException(com.golf.course.dto.StartCheckView checkView) {
        super("赛事「" + checkView.tournamentName() + "」开赛校验未通过："
                + checkView.blockedHoles().size() + " 个应打球洞不可用，赛事仍保持「报名中」，"
                + "请按提示修复球洞后重新校验开赛");
        this.checkView = checkView;
    }

    public com.golf.course.dto.StartCheckView checkView() {
        return checkView;
    }
}
