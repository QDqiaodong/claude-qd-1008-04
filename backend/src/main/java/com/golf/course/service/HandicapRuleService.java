package com.golf.course.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.golf.course.common.BizException;
import com.golf.course.entity.HandicapRule;
import com.golf.course.repository.HandicapHistoryRepository;
import com.golf.course.repository.HandicapRuleRepository;
import com.golf.course.repository.HandicapRunRepository;

/**
 * 差点计算规则维护：带生效日期的版本化规则。
 *
 * <p>核心规则：
 * <ol>
 *   <li>同一生效日期只允许一条规则；</li>
 *   <li>规则一旦被任何计算 / 批次引用即不可删除——历史差点必须按当时规则可追溯，
 *       要改口径只能新增一条更新生效日期的规则；</li>
 *   <li>bestCountTable「样本轮数:采用个数」必须可解析、采用数落在 1 ~ 样本数之间，
 *       且最小档 ≤ 最少样本轮次（保证任何达标样本都能取到采用个数）。</li>
 * </ol>
 */
@Service
public class HandicapRuleService {

    private final HandicapRuleRepository ruleRepository;
    private final HandicapHistoryRepository historyRepository;
    private final HandicapRunRepository runRepository;

    public HandicapRuleService(HandicapRuleRepository ruleRepository,
                               HandicapHistoryRepository historyRepository,
                               HandicapRunRepository runRepository) {
        this.ruleRepository = ruleRepository;
        this.historyRepository = historyRepository;
        this.runRepository = runRepository;
    }

    /** 全部规则（新生效日期在前），并标注是否已被引用。 */
    @Transactional(readOnly = true)
    public List<HandicapRule> list() {
        List<HandicapRule> rules = ruleRepository.findAllByOrderByEffectiveDateDescIdDesc();
        for (HandicapRule rule : rules) {
            rule.setReferenced(historyRepository.existsByRuleId(rule.getId())
                    || runRepository.existsByRuleId(rule.getId()));
        }
        return rules;
    }

    @Transactional
    public HandicapRule create(HandicapRule input) {
        if (input.getRuleName() == null || input.getRuleName().isBlank()) {
            throw new BizException("规则名称不能为空");
        }
        if (input.getEffectiveDate() == null) {
            throw new BizException("生效日期不能为空");
        }
        ruleRepository.findByEffectiveDate(input.getEffectiveDate()).ifPresent(r -> {
            throw new BizException("生效日期 " + input.getEffectiveDate() + " 已存在规则「" + r.getRuleName()
                    + "」，同一生效日期只允许一条规则；如需调整请换一个生效日期");
        });
        if (input.getRoundsWindow() == null || input.getRoundsWindow() < 1) {
            throw new BizException("最近轮次窗口须 ≥ 1（WHS 标准为 20）");
        }
        if (input.getMinRounds() == null || input.getMinRounds() < 1) {
            throw new BizException("最少样本轮次须 ≥ 1（WHS 标准为 3）");
        }
        if (input.getMinRounds() > input.getRoundsWindow()) {
            throw new BizException("最少样本轮次不能大于最近轮次窗口");
        }
        // 解析即校验：非法映射表在建规则时就拦住，绝不让坏规则进入计算
        TreeMap<Integer, Integer> table = parseBestCountTable(input.getBestCountTable());
        if (table.firstKey() > input.getMinRounds()) {
            throw new BizException("采用个数映射表的最小样本档（" + table.firstKey()
                    + "）必须 ≤ 最少样本轮次（" + input.getMinRounds() + "），否则达标样本取不到采用个数");
        }
        if (input.getMaxDifferential() == null || input.getMaxDifferential().signum() <= 0) {
            throw new BizException("单轮差值截断上限须 > 0");
        }
        if (input.getMaxDrop() == null || input.getMaxDrop().signum() < 0) {
            throw new BizException("单次下调幅度上限须 ≥ 0");
        }

        HandicapRule rule = new HandicapRule();
        rule.setRuleName(input.getRuleName().trim());
        rule.setEffectiveDate(input.getEffectiveDate());
        rule.setRoundsWindow(input.getRoundsWindow());
        rule.setMinRounds(input.getMinRounds());
        rule.setBestCountTable(input.getBestCountTable().trim());
        rule.setMaxDifferential(input.getMaxDifferential());
        rule.setMaxDrop(input.getMaxDrop());
        rule.setNote(input.getNote());
        rule.setCreatedAt(LocalDateTime.now());
        return ruleRepository.save(rule);
    }

    /**
     * 删除规则。已被计算或批次引用的规则禁止删除——否则历史差点将失去
     * 「当时用的是什么口径」的直接证据，只能新增带新生效日期的规则来替代。
     */
    @Transactional
    public void delete(Long id) {
        HandicapRule rule = ruleRepository.findById(id)
                .orElseThrow(() -> new BizException("规则不存在：" + id));
        if (historyRepository.existsByRuleId(id) || runRepository.existsByRuleId(id)) {
            throw new BizException("规则「" + rule.getRuleName() + "」已被差点计算引用，"
                    + "为保证历史指数按当时规则可追溯，不能删除；如需调整请新增一条带新生效日期的规则");
        }
        ruleRepository.delete(rule);
    }

    /**
     * 解析「样本轮数:采用个数」映射表，如 {@code 3:1,4:1,5:1,6:2,...,20:8}。
     * 校验：每档为 正整数:正整数，采用数 ≤ 样本数；重复样本档直接拒绝。
     */
    public static TreeMap<Integer, Integer> parseBestCountTable(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException("采用个数映射表不能为空，格式如 3:1,4:1,5:1,6:2,...,20:8");
        }
        TreeMap<Integer, Integer> table = new TreeMap<>();
        for (String part : raw.split(",")) {
            String[] kv = part.trim().split(":");
            if (kv.length != 2) {
                throw new BizException("采用个数映射表格式有误：「" + part.trim() + "」，应为 样本轮数:采用个数");
            }
            final int sample;
            final int count;
            try {
                sample = Integer.parseInt(kv[0].trim());
                count = Integer.parseInt(kv[1].trim());
            } catch (NumberFormatException e) {
                throw new BizException("采用个数映射表含非数字项：「" + part.trim() + "」");
            }
            if (sample < 1) {
                throw new BizException("采用个数映射表的样本轮数须 ≥ 1：「" + part.trim() + "」");
            }
            if (count < 1 || count > sample) {
                throw new BizException("采用个数须落在 1 ~ 样本轮数之间：「" + part.trim() + "」");
            }
            if (table.put(sample, count) != null) {
                throw new BizException("采用个数映射表存在重复样本档：" + sample);
            }
        }
        return table;
    }

    /** 按样本轮数取应采用的最差值个数：样本数落在两档之间时向下取最近档。 */
    public static int bestCountFor(TreeMap<Integer, Integer> table, int sampleSize) {
        Map.Entry<Integer, Integer> entry = table.floorEntry(sampleSize);
        if (entry == null) {
            throw new BizException("采用个数映射表没有覆盖样本轮数 " + sampleSize + "，请检查规则配置");
        }
        return entry.getValue();
    }
}
