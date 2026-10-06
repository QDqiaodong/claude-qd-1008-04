package com.golf.course.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 裁判裁决争议卡：对争议洞逐项「维持原成绩」（ruledStrokes 传原杆数或显式等于原杆数）
 * 或「给出修正杆数」（ruledStrokes 传新杆数，≥1），每项都必须写裁决说明。
 * 裁决后整卡回到「待球员确认」，重新固化快照。
 */
public record RulingRequest(
        @NotBlank(message = "裁判姓名不能为空") String referee,
        @NotNull(message = "缺少版本号，请刷新页面后重试") Long expectedVersion,
        @NotNull(message = "请逐项给出裁决") List<Item> rulings) {

    public record Item(
            @NotNull(message = "裁决洞号不能为空") Integer holeNo,
            @NotNull(message = "裁决杆数不能为空（维持则填原杆数）") Integer ruledStrokes,
            @NotBlank(message = "裁决说明不能为空") String note) {
    }
}
