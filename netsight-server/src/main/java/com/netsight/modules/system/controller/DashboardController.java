package com.netsight.modules.system.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.netsight.common.core.R;
import com.netsight.modules.alert.entity.NotificationLog;
import com.netsight.modules.alert.mapper.EventRecordMapper;
import com.netsight.modules.alert.mapper.NotificationLogMapper;
import com.netsight.modules.inspection.entity.InspectionPlan;
import com.netsight.modules.inspection.entity.InspectionTask;
import com.netsight.modules.inspection.mapper.InspectionPlanMapper;
import com.netsight.modules.inspection.mapper.InspectionTaskMapper;
import com.netsight.modules.knowledge.entity.FaultArticle;
import com.netsight.modules.knowledge.mapper.FaultArticleMapper;
import com.netsight.modules.notice.entity.Notice;
import com.netsight.modules.notice.mapper.NoticeMapper;
import com.netsight.modules.spare.entity.SparePartRecord;
import com.netsight.modules.spare.mapper.SparePartMapper;
import com.netsight.modules.spare.mapper.SparePartRecordMapper;
import com.netsight.modules.system.entity.Device;
import com.netsight.modules.system.mapper.DeviceMapper;
import com.netsight.modules.system.mapper.EdgeGatewayMapper;
import com.netsight.modules.system.mapper.SysTenantMapper;
import com.netsight.modules.system.mapper.SysUserMapper;
import com.netsight.modules.workorder.entity.Repairer;
import com.netsight.modules.workorder.entity.WorkOrder;
import com.netsight.modules.workorder.mapper.RepairerMapper;
import com.netsight.modules.workorder.mapper.WorkOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
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
    private final FaultArticleMapper faultArticleMapper;
    private final NoticeMapper noticeMapper;
    private final InspectionPlanMapper inspectionPlanMapper;
    private final InspectionTaskMapper inspectionTaskMapper;
    private final NotificationLogMapper notificationLogMapper;
    private final RepairerMapper repairerMapper;
    private final SysUserMapper sysUserMapper;
    private final SysTenantMapper sysTenantMapper;
    private final SparePartRecordMapper spareRecordMapper;

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
        result.put("knowledge", knowledgeOverview());
        result.put("notice", noticeOverview());
        result.put("inspection", inspectionOverview());
        result.put("notify", notifyOverview());
        result.put("ops", opsOverview());
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
        // 近7天新增工单趋势（补零）
        List<Map<String, Object>> trend = new ArrayList<>();
        Map<String, Long> byDay = new HashMap<>();
        for (Map<String, Object> row : orderMapper.selectMaps(
                new QueryWrapper<WorkOrder>().select("DATE(create_time) AS d", "COUNT(*) AS cnt")
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
        // 低库存 TOP5 明细（数量升序取最缺的前5；spare_part 无 part_name 列，名称按类型+品牌+型号+序列号拼接）
        List<Map<String, Object>> lowStockList = new ArrayList<>();
        for (Map<String, Object> row : sparePartMapper.selectMaps(
                new QueryWrapper<com.netsight.modules.spare.entity.SparePart>()
                        .select("part_no", "part_type", "brand", "model", "serial_no", "quantity", "safe_stock")
                        .gt("safe_stock", 0).apply("quantity <= safe_stock")
                        .orderByAsc("quantity").last("LIMIT 5"))) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("partNo", str(row.get("part_no")));
            item.put("partName", buildPartName(row));
            item.put("quantity", num(row.get("quantity")));
            item.put("safeStock", num(row.get("safe_stock")));
            lowStockList.add(item);
        }
        data.put("lowStockList", lowStockList);
        return data;
    }

    // ==================== 知识库概览 ====================

    private Map<String, Object> knowledgeOverview() {
        Map<String, Object> data = new LinkedHashMap<>();
        // 启用中的故障知识文章总数
        Long total = faultArticleMapper.selectCount(
                new QueryWrapper<FaultArticle>().eq("status", 1));
        data.put("total", total == null ? 0 : total);
        // 分类分布 TOP5（按文章数倒序）
        List<Map<String, Object>> byCategory = new ArrayList<>();
        for (Map<String, Object> row : faultArticleMapper.selectMaps(
                new QueryWrapper<FaultArticle>().select("category", "COUNT(*) AS cnt")
                        .eq("status", 1).isNotNull("category").ne("category", "")
                        .groupBy("category").orderByDesc("cnt").last("LIMIT 5"))) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("category", str(row.get("category")));
            item.put("count", num(row.get("cnt")));
            byCategory.add(item);
        }
        data.put("byCategory", byCategory);
        return data;
    }

    // ==================== 通知公告概览 ====================

    private Map<String, Object> noticeOverview() {
        Map<String, Object> data = new LinkedHashMap<>();
        Long total = noticeMapper.selectCount(
                new QueryWrapper<Notice>().eq("status", 1));
        data.put("total", total == null ? 0 : total);
        // 最新发布 3 条公告
        List<Map<String, Object>> latest = new ArrayList<>();
        for (Notice n : noticeMapper.selectList(new QueryWrapper<Notice>()
                .eq("status", 1).orderByDesc("publish_time").last("LIMIT 3"))) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("title", n.getTitle() == null ? "" : n.getTitle());
            item.put("level", n.getLevel() == null ? "" : n.getLevel());
            item.put("isTop", n.getIsTop() == null ? 0 : n.getIsTop());
            item.put("publishTime", n.getPublishTime() == null ? ""
                    : n.getPublishTime().format(DateTimeFormatter.ofPattern("MM-dd HH:mm")));
            latest.add(item);
        }
        data.put("latest", latest);
        return data;
    }

    // ==================== 点检巡检概览 ====================

    private Map<String, Object> inspectionOverview() {
        Map<String, Object> data = new LinkedHashMap<>();
        Long plans = inspectionPlanMapper.selectCount(
                new QueryWrapper<InspectionPlan>().eq("status", 1));
        data.put("plans", plans == null ? 0 : plans);
        LocalDate today = LocalDate.now();
        // 今日点检任务：plan_date=今天；已完成：status=2
        Long todayTasks = inspectionTaskMapper.selectCount(
                new QueryWrapper<InspectionTask>().eq("plan_date", today));
        Long todayDone = inspectionTaskMapper.selectCount(
                new QueryWrapper<InspectionTask>().eq("plan_date", today).eq("status", 2));
        long tasks = todayTasks == null ? 0 : todayTasks;
        long done = todayDone == null ? 0 : todayDone;
        data.put("todayTasks", tasks);
        data.put("todayDone", done);
        data.put("doneRate", tasks == 0 ? 0 : Math.round(done * 10000.0 / tasks) / 100.0);
        return data;
    }

    // ==================== 通知发送监控 ====================

    private Map<String, Object> notifyOverview() {
        Map<String, Object> data = new LinkedHashMap<>();
        LocalDateTime dayStart = LocalDate.now().atStartOfDay();
        Long today = notificationLogMapper.selectCount(
                new QueryWrapper<NotificationLog>().ge("create_time", dayStart));
        Long success = notificationLogMapper.selectCount(
                new QueryWrapper<NotificationLog>().ge("create_time", dayStart).eq("success", 1));
        Long failed = notificationLogMapper.selectCount(
                new QueryWrapper<NotificationLog>().ge("create_time", dayStart).eq("success", 0));
        long t = today == null ? 0 : today;
        long s = success == null ? 0 : success;
        data.put("today", t);
        data.put("success", s);
        data.put("failed", failed == null ? 0 : failed);
        data.put("successRate", t == 0 ? 0 : Math.round(s * 10000.0 / t) / 100.0);
        return data;
    }

    // ==================== 今日运营速览 ====================

    private Map<String, Object> opsOverview() {
        Map<String, Object> data = new LinkedHashMap<>();
        Long repairers = repairerMapper.selectCount(null);
        Long onDuty = repairerMapper.selectCount(
                new QueryWrapper<Repairer>().eq("status", 1));
        // sys_tenant 在租户拦截器 IGNORE_TABLES 中，天然全局（超管/租户管理员一致）
        Long tenants = sysTenantMapper.selectCount(null);
        // sys_user 受租户拦截过滤：租户管理员=本租户用户数，超管=全部
        Long users = sysUserMapper.selectCount(null);
        // 今日出入库业务笔数（体现当天运维活跃度）
        Long todayStock = spareRecordMapper.selectCount(
                new QueryWrapper<SparePartRecord>().ge("create_time", LocalDate.now().atStartOfDay()));
        data.put("repairers", repairers == null ? 0 : repairers);
        data.put("onDuty", onDuty == null ? 0 : onDuty);
        data.put("tenants", tenants == null ? 0 : tenants);
        data.put("users", users == null ? 0 : users);
        data.put("todayStock", todayStock == null ? 0 : todayStock);
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

    /** 备件显示名称：类型 + 品牌 + 型号 + 序列号（与 SparePartService.partName 口径一致） */
    private String buildPartName(Map<String, Object> row) {
        StringBuilder sb = new StringBuilder();
        appendPartField(sb, row.get("part_type"));
        appendPartField(sb, row.get("brand"));
        appendPartField(sb, row.get("model"));
        appendPartField(sb, row.get("serial_no"));
        return sb.toString();
    }

    private void appendPartField(StringBuilder sb, Object v) {
        if (v != null && StringUtils.hasText(String.valueOf(v))) {
            sb.append(' ').append(v);
        }
    }
}
