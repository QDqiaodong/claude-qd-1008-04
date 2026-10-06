package com.golf.course.dto;

import java.time.LocalDate;

/**
 * 全量重算请求。
 *
 * @param asOfDate    计算基准日；为空取当天。只取基准日（含）之前已结束的轮次，
 *                    并据此选取「已生效的最新规则」
 * @param fromDate    赛事日期分批范围起点（按赛事结束日过滤）；为空表示不限
 * @param toDate      赛事日期分批范围终点；为空表示不限
 * @param batchSize   每批赛事场数（默认 20）
 * @param triggeredBy 操作人
 */
public record RunRequest(LocalDate asOfDate, LocalDate fromDate, LocalDate toDate,
                         Integer batchSize, String triggeredBy) {
}
