package com.netsight.modules.system.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.netsight.common.core.R;
import com.netsight.modules.alert.mapper.EventRecordMapper;
import com.netsight.modules.spare.mapper.SparePartMapper;
import com.netsight.modules.system.entity.Device;
import com.netsight.modules.system.mapper.DeviceMapper;
import com.netsight.modules.system.mapper.EdgeGatewayMapper;
import com.netsight.modules.workorder.entity.WorkOrder;
import com.netsight.modules.workorder.mapper.WorkOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运维监控大屏聚合接口（方案7章）
 * 一次返回：设备概览/事件统计/工单看板/网关状态/备件库存
 * 数据隔离：MyBatis-Plus 多租户插件自动追加 tenant_id（超管忽略）
 */
@Slf4j
@RestController
@RequestMapping("/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final DeviceMapper deviceMapper;
    private final EventRecordMapper eventMapper;
    private final WorkOrderMapper orderMapper;
    private final EdgeGatewayMapper gatewayMapper;
    private final SparePartMapper sparePartMapper;

    /**
     * 大屏聚合数据
     * GET /dashboard/overview
     */
    @GetMapping("/overview")
    @PreAuthorize("hasRole('super_admin') or hasAuthority('dashboard:view')")
    public R<Map<String, Object>> overview() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("device", deviceOverview());
        result.put("event", eventOverview());
        result.put("order", orderOverview());
        result.put("gateway", gatewayOverview());
        result.put("spare", spareOverview());
        result.put("generatedAt", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        return R.ok(result);
    }

    // ==================== 设备概览 ====================

    private Map<String, Object> deviceOverview() {
        Map<String, Object> data = new LinkedHashMap<>();
        // 状态分组：status=1 在线 / 0 离线
        Map<Integer, Long> byStatus = groupCount(deviceMapper.selectMaps(
                new QueryWrapper<Device>().select("status", "COUNT(*) AS cnt").groupBy("status")), "status");
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        long online = byStatus.getOrDefault(1, 0L);
        long offline = byStatus.getOrDefault(0, 0L);
        // 链路异常（status=1 且 line_status=1）
        Long lineAbnormal = deviceMapper.selectCount(new QueryWrapper<Device>()
                .eq("status", 1).eq("line_status", 1));
        data.put("total", total);
        data.put("online", online);
        data.put("offline", offline);
        data.put("lineAbnormal", lineAbnormal == null ? 0 : lineAbnormal);
        data.put("onlineRate", total == 0 ? 0 : Math.round(online * 10000.0 / total) / 100.0);
        // 按类型分布
        List<Map<String, Object>> byType = new ArrayList<>();
        List<Map<String, Object>> raw = deviceMapper.selectMaps(new QueryWrapper<Device>()
                .select("device_type", "status", "COUNT(*) AS cnt").groupBy("device_type", "status"));
        Map<String, Map<String, Object>> typeMap = new LinkedHashMap<>();
        for (Map<String, Object> row : raw) {
            String type = str(row.get("device_type"));
            Map<String, Object> item = typeMap.computeIfAbsent(type, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("deviceType", k);
                m.put("total", 0L);
                m.put("online", 0L);
                m.put("offline", 0L);
                return m;
            });
            long cnt = num(row.get("cnt"));
            item.put("total", num(item.get("total")) + cnt);
            if (num(row.get("status")) == 1) {
                item.put("online", num(item.get("online")) + cnt);
            } else {
                item.put("offline", num(item.get("offline")) + cnt);
            }
        }
        byType.addAll(typeMap.values());
        data.put("byType", byType);
        return data;
    }

    // ==================== 事件统计 ====================

    private Map<String, Object> eventOverview() {
        Map<String, Object> data = new LinkedHashMap<>();
        LocalDateTime dayStart = LocalDate.now().atStartOfDay();
        // 今日告警总数
        Long today = eventMapper.selectCount(new QueryWrapper<com.netsight.modules.alert.entity.EventRecord>()
                .ge("create_time", dayStart));
        data.put("today", today == null ? 0 : today);
        // 严重级别分组（全部时间）
        Map<String, Long> bySeverity = new LinkedHashMap<>();
        for (Map<String, Object> row : eventMapper.selectMaps(
                new QueryWrapper<com.netsight.modules.alert.entity.EventRecord>()
                        .select("severity", "COUNT(*) AS cnt").groupBy("severity"))) {
            bySeverity.put(str(row.get("severity")), num(row.get("cnt")));
        }
        data.put("critical", bySeverity.getOrDefault("critical", 0L));
        data.put("warning", bySeverity.getOrDefault("warning", 0L));
        data.put("info", bySeverity.getOrDefault("info", 0L));
        // 近7天趋势（补零）
        List<Map<String, Object>> trend = new ArrayList<>();
        Map<String, Long> byDay = new HashMap<>();
        for (Map<String, Object> row : eventMapper.selectMaps(
                new QueryWrapper<com.netsight.modules.alert.entity.EventRecord>()
                        .select("DATE(create_time) AS d", "COUNT(*) AS cnt")
                        .ge("create_time", LocalDate.now().minusDays(6).atStartOfDay())
                        .groupBy("d"))) {
            byDay.put(str(row.get("d")), num(row.get("cnt")));
        }
        for (int i = 6; i >= 0; i--) {
            String day = LocalDate.now().minusDays(i).format(DATE_FMT);
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", day.substring(5));
            point.put("count", byDay.getOrDefault(day, 0L));
            trend.add(point);
        }
        data.put("trend", trend);
        // TOP 告警设备
        List<Map<String, Object>> topDevices = new ArrayList<>();
        for (Map<String, Object> row : eventMapper.selectMaps(
                new QueryWrapper<com.netsight.modules.alert.entity.EventRecord>()
                        .select("device_name", "COUNT(*) AS cnt")
                        .isNotNull("device_name").ne("device_name", "")
                        .groupBy("device_name").orderByDesc("cnt").last("LIMIT 5"))) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("deviceName", str(row.get("device_name")));
            item.put("count", num(row.get("cnt")));
            topDevices.add(item);
        }
        data.put("topDevices", topDevices);
        return data;
    }

    // ==================== 工单看板 ====================

    private Map<String, Object> orderOverview() {
        Map<String, Object> data = new LinkedHashMap<>();
        Map<Integer, Long> byStatus = groupCount(orderMapper.selectMaps(
                new QueryWrapper<WorkOrder>().select("status", "COUNT(*) AS cnt").groupBy("status")), "status");
        data.put("pending", byStatus.getOrDefault(0, 0L));
        data.put("dispatched", byStatus.getOrDefault(1, 0L));
        data.put("repairing", byStatus.getOrDefault(2, 0L));
        data.put("completed", byStatus.getOrDefault(3, 0L));
        data.put("closed", byStatus.getOrDefault(4, 0L));
        data.put("recovered", byStatus.getOrDefault(5, 0L));
        data.put("total", byStatus.values().stream().mapToLong(Long::longValue).sum());
        return data;
    }

    // ==================== 网关状态 ====================

    private Map<String, Object> gatewayOverview() {
        Map<String, Object> data = new LinkedHashMap<>();
        Map<Integer, Long> byStatus = groupCount(gatewayMapper.selectMaps(
                new QueryWrapper<com.netsight.modules.system.entity.EdgeGateway>()
                        .select("online_status", "COUNT(*) AS cnt").groupBy("online_status")), "online_status");
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        data.put("total", total);
        data.put("online", byStatus.getOrDefault(1, 0L));
        data.put("offline", byStatus.getOrDefault(0, 0L));
        return data;
    }

    // ==================== 备件库存 ====================

    private Map<String, Object> spareOverview() {
        Map<String, Object> data = new LinkedHashMap<>();
        Long partTypes = sparePartMapper.selectCount(
                new QueryWrapper<com.netsight.modules.spare.entity.SparePart>()
                        .select("DISTINCT part_type"));
        data.put("partTypes", partTypes == null ? 0 : partTypes);
        // 库存总量（忽略返修/报废状态的流转件）
        List<Map<String, Object>> rows = sparePartMapper.selectMaps(
                new QueryWrapper<com.netsight.modules.spare.entity.SparePart>()
                        .select("IFNULL(SUM(quantity),0) AS total"));
        data.put("totalQuantity", rows.isEmpty() ? 0 : num(rows.get(0).get("total")));
        // 低库存数量
        Long low = sparePartMapper.selectCount(
                new QueryWrapper<com.netsight.modules.spare.entity.SparePart>()
                        .gt("safe_stock", 0).apply("quantity <= safe_stock"));
        data.put("lowStock", low == null ? 0 : low);
        return data;
    }

    // ==================== 工具 ====================

    /** 分组计数结果转 Map<Integer, Long> */
    private Map<Integer, Long> groupCount(List<Map<String, Object>> rows, String key) {
        Map<Integer, Long> map = new HashMap<>();
        for (Map<String, Object> row : rows) {
            map.put((int) num(row.get(key)), num(row.get("cnt")));
        }
        return map;
    }

    private long num(Object v) {
        if (v == null) {
            return 0;
        }
        return ((Number) v).longValue();
    }

    private String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }
}
