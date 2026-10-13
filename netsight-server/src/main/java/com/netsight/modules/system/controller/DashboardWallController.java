package com.netsight.modules.system.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.netsight.common.core.R;
import com.netsight.framework.security.SecurityUtils;
import com.netsight.modules.system.entity.DashboardWallConfig;
import com.netsight.modules.system.mapper.DashboardWallConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 驾驶舱上墙配置接口（3×3 上墙模式）
 * 按租户保存「上墙模式」勾选的大屏板块及顺序，最多 9 个
 * GET  /dashboard/wall/config  查询当前租户上墙配置
 * POST /dashboard/wall/config  全量覆盖保存（body: 有序 boardKey 数组）
 */
@Slf4j
@RestController
@RequestMapping("/dashboard/wall")
@RequiredArgsConstructor
public class DashboardWallController {

    /** 全部可上墙板块定义（key 与前端 BOARD_DEFS 保持一致，名称以服务端为准） */
    private static final Map<String, String> BOARD_DEFS = new LinkedHashMap<>();
    static {
        BOARD_DEFS.put("metric_summary", "核心指标");
        BOARD_DEFS.put("type_chart", "设备类型分布");
        BOARD_DEFS.put("status_chart", "设备状态占比");
        BOARD_DEFS.put("gateway", "边缘网关状态");
        BOARD_DEFS.put("spare", "备件库存概览");
        BOARD_DEFS.put("knowledge", "知识库与公告");
        BOARD_DEFS.put("trend_chart", "近7日告警趋势");
        BOARD_DEFS.put("live_alert", "实时告警");
        BOARD_DEFS.put("inspection", "点检执行情况");
        BOARD_DEFS.put("order_trend", "近7日工单新增");
        BOARD_DEFS.put("order_board", "工单看板");
        BOARD_DEFS.put("alert_level", "告警级别统计");
        BOARD_DEFS.put("notify", "通知发送监控");
        BOARD_DEFS.put("ops", "今日运营速览");
    }

    /** 上墙最大数量（3×3 九宫格） */
    private static final int MAX_WALL = 9;

    private final DashboardWallConfigMapper wallMapper;

    /**
     * 查询当前租户上墙配置（按 sort_order 升序）
     */
    @GetMapping("/config")
    @PreAuthorize("hasRole('super_admin') or hasAuthority('dashboard:view')")
    public R<List<Map<String, String>>> getConfig() {
        Long tenantId = SecurityUtils.getTenantId();
        List<DashboardWallConfig> list = wallMapper.selectList(
                new LambdaQueryWrapper<DashboardWallConfig>()
                        .eq(DashboardWallConfig::getTenantId, tenantId)
                        .orderByAsc(DashboardWallConfig::getSortOrder));
        List<Map<String, String>> result = new ArrayList<>();
        for (DashboardWallConfig cfg : list) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("boardKey", cfg.getBoardKey());
            item.put("boardName", cfg.getBoardName());
            result.add(item);
        }
        return R.ok(result);
    }

    /**
     * 全量覆盖保存上墙配置
     * body: ["metric_summary","type_chart",...] 有序数组，最多 9 个
     * 先删除当前租户全部旧配置，再按新顺序批量写入（事务由 Service 层处理）
     */
    @PostMapping("/config")
    @PreAuthorize("hasRole('super_admin') or hasAuthority('dashboard:view')")
    public R<Void> saveConfig(@RequestBody List<String> boardKeys) {
        // key 合法性校验（空数组合法：表示一个都不上墙）
        if (!CollectionUtils.isEmpty(boardKeys)) {
            if (boardKeys.size() > MAX_WALL) {
                return R.fail("上墙板块最多 " + MAX_WALL + " 个，请调整后重试");
            }
            // 去重校验
            if (boardKeys.stream().distinct().count() != boardKeys.size()) {
                return R.fail("上墙板块存在重复，请调整后重试");
            }
            for (String key : boardKeys) {
                if (!StringUtils.hasText(key) || !BOARD_DEFS.containsKey(key)) {
                    return R.fail("包含未知板块：" + key);
                }
            }
        }
        Long tenantId = SecurityUtils.getTenantId();
        // 删除旧配置（空数组同样需删除，实现"全部不上墙"）
        wallMapper.delete(new LambdaQueryWrapper<DashboardWallConfig>()
                .eq(DashboardWallConfig::getTenantId, tenantId));
        // 写入新配置
        int sort = 1;
        for (String key : boardKeys) {
            DashboardWallConfig cfg = new DashboardWallConfig();
            cfg.setTenantId(tenantId);
            cfg.setBoardKey(key);
            cfg.setBoardName(BOARD_DEFS.get(key));
            cfg.setSortOrder(sort++);
            wallMapper.insert(cfg);
        }
        log.info("租户 {} 保存上墙配置：{}", tenantId, boardKeys);
        return R.ok();
    }
}
