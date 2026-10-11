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
import org.springframework.web.bind.annotation.RequestParam;
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
     * GET /dashboard/overview?demo=0
     * demo=1：返回演示模式模拟数据（每个板块 100 条级，丰富展示效果），不触碰任何真实业务表
     */
    @GetMapping("/overview")
    @PreAuthorize("hasRole('super_admin') or hasAuthority('dashboard:view')")
    public R<Map<String, Object>> overview(@RequestParam(defaultValue = "0") int demo) {
        if (demo == 1) {
            Map<String, Object> demoData = demoOverview();
            demoData.put("recentEvents", demoRecentEvents());
            demoData.put("generatedAt", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            return R.ok(demoData);
        }
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
        // 近 7 日点检执行趋势（柱状图）：每天任务数与完成数
        List<Map<String, Object>> weekTrend = new ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            Long dayTasks = inspectionTaskMapper.selectCount(
                    new QueryWrapper<InspectionTask>().eq("plan_date", d));
            Long dayDone = inspectionTaskMapper.selectCount(
                    new QueryWrapper<InspectionTask>().eq("plan_date", d).eq("status", 2));
            Map<String, Object> day = new LinkedHashMap<>();
            day.put("date", d.format(DateTimeFormatter.ofPattern("MM-dd")));
            day.put("tasks", dayTasks == null ? 0 : dayTasks);
            day.put("done", dayDone == null ? 0 : dayDone);
            weekTrend.add(day);
        }
        data.put("weekTrend", weekTrend);
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

    // ==================== 演示模式（demo=1，纯内存模拟，不碰真实表） ====================

    /**
     * 演示模式聚合数据：每个板块均模拟 100 条级的展示效果，
     * 结构字段与真实模式完全一致（前端零适配）。
     */
    private Map<String, Object> demoOverview() {
        Map<String, Object> result = new LinkedHashMap<>();

        // 设备概览：total=100，在线 72 / 离线 28，链路异常 4
        Map<String, Object> device = new LinkedHashMap<>();
        device.put("total", 100);
        device.put("online", 72);
        device.put("offline", 28);
        device.put("lineAbnormal", 4);
        device.put("onlineRate", 72.0);
        List<Map<String, Object>> byType = new ArrayList<>();
        addType(byType, "network", 22, 8);
        addType(byType, "camera", 20, 8);
        addType(byType, "door_controller", 12, 4);
        addType(byType, "nvr", 9, 3);
        addType(byType, "server", 5, 3);
        addType(byType, "switch", 4, 2);
        device.put("byType", byType);
        result.put("device", device);

        // 事件统计：今日 100，级别 42/36/22，7 天趋势，TOP5 设备
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("today", 100);
        event.put("critical", 42);
        event.put("warning", 36);
        event.put("info", 22);
        List<Map<String, Object>> eventTrend = new ArrayList<>();
        int[] eventDays = {86, 112, 95, 130, 104, 88, 100};
        for (int i = 0; i < 7; i++) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("date", LocalDate.now().minusDays(6 - i).format(DATE_FMT).substring(5));
            p.put("count", eventDays[i]);
            eventTrend.add(p);
        }
        event.put("trend", eventTrend);
        List<Map<String, Object>> topDevices = new ArrayList<>();
        addTop(topDevices, "大华摄像头", 1080);
        addTop(topDevices, "大华人脸识别主机", 960);
        addTop(topDevices, "车间枪机", 520);
        addTop(topDevices, "接入交换机", 410);
        addTop(topDevices, "门禁控制器", 330);
        event.put("topDevices", topDevices);
        result.put("event", event);

        // 工单看板：合计 100
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("pending", 25);
        order.put("dispatched", 20);
        order.put("repairing", 15);
        order.put("completed", 25);
        order.put("closed", 10);
        order.put("recovered", 5);
        order.put("total", 100);
        List<Map<String, Object>> orderTrend = new ArrayList<>();
        int[] orderDays = {12, 18, 15, 22, 16, 14, 20};
        for (int i = 0; i < 7; i++) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("date", LocalDate.now().minusDays(6 - i).format(DATE_FMT).substring(5));
            p.put("count", orderDays[i]);
            orderTrend.add(p);
        }
        order.put("trend", orderTrend);
        result.put("order", order);

        // 网关：100 台网关，在线 93
        Map<String, Object> gateway = new LinkedHashMap<>();
        gateway.put("total", 100);
        gateway.put("online", 93);
        gateway.put("offline", 7);
        result.put("gateway", gateway);

        // 备件库存：12 类 / 总量 1024 / 低库存 8 + TOP5 明细
        Map<String, Object> spare = new LinkedHashMap<>();
        spare.put("partTypes", 12);
        spare.put("totalQuantity", 1024);
        spare.put("lowStock", 8);
        List<Map<String, Object>> lowStockList = new ArrayList<>();
        addSpare(lowStockList, "SHP-001", "视频监控-海康-DS-2CD-001", 3, 10);
        addSpare(lowStockList, "SHP-002", "门禁-大华-ASI41-002", 2, 8);
        addSpare(lowStockList, "SHP-003", "网络-华为-S5735-003", 4, 12);
        addSpare(lowStockList, "SHP-004", "电源-明纬-S-35-004", 5, 15);
        addSpare(lowStockList, "SHP-005", "硬盘-希捷-4T-005", 6, 20);
        spare.put("lowStockList", lowStockList);
        result.put("spare", spare);

        // 知识库：100 篇，5 类各 20
        Map<String, Object> knowledge = new LinkedHashMap<>();
        knowledge.put("total", 100);
        List<Map<String, Object>> byCategory = new ArrayList<>();
        addCategory(byCategory, "视频故障", 24);
        addCategory(byCategory, "设备离线", 22);
        addCategory(byCategory, "门禁故障", 20);
        addCategory(byCategory, "链路异常", 18);
        addCategory(byCategory, "性能问题", 16);
        knowledge.put("byCategory", byCategory);
        result.put("knowledge", knowledge);

        // 通知公告：100 条，最新 3 条
        Map<String, Object> notice = new LinkedHashMap<>();
        notice.put("total", 100);
        List<Map<String, Object>> latest = new ArrayList<>();
        addNotice(latest, "【重要】NetSight 平台 V1.4 版本升级完成通知", "important", 1, "10-11 09:30");
        addNotice(latest, "机房巡检计划调整通知（本周六维护窗口）", "normal", 0, "10-10 18:00");
        addNotice(latest, "设备固件升级批次安排公告", "normal", 0, "10-09 14:20");
        notice.put("latest", latest);
        result.put("notice", notice);

        // 点检巡检：100 计划 / 今日任务 100 / 完成 64 + 近 7 日趋势（任务/完成）
        Map<String, Object> inspection = new LinkedHashMap<>();
        inspection.put("plans", 100);
        inspection.put("todayTasks", 100);
        inspection.put("todayDone", 64);
        inspection.put("doneRate", 64.0);
        java.util.Random inspRnd = new java.util.Random(20261012L);
        List<Map<String, Object>> inspWeek = new ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            LocalDate d = LocalDate.now().minusDays(i);
            int tasks = 88 + inspRnd.nextInt(13);            // 88-100
            int done = (int) Math.round(tasks * (0.58 + inspRnd.nextDouble() * 0.16)); // 完成率 58%-74%
            Map<String, Object> day = new LinkedHashMap<>();
            day.put("date", d.format(DateTimeFormatter.ofPattern("MM-dd")));
            day.put("tasks", tasks);
            day.put("done", Math.min(done, tasks));
            inspWeek.add(day);
        }
        inspection.put("weekTrend", inspWeek);
        result.put("inspection", inspection);

        // 通知发送监控：今日 100，成功 96
        Map<String, Object> notify = new LinkedHashMap<>();
        notify.put("today", 100);
        notify.put("success", 96);
        notify.put("failed", 4);
        notify.put("successRate", 96.0);
        result.put("notify", notify);

        // 今日运营速览
        Map<String, Object> ops = new LinkedHashMap<>();
        ops.put("repairers", 100);
        ops.put("onDuty", 78);
        ops.put("tenants", 100);
        ops.put("users", 200);
        ops.put("todayStock", 100);
        result.put("ops", ops);

        return result;
    }

    /** 演示模式：100 条实时告警（LIVE 列表） */
    private List<Map<String, Object>> demoRecentEvents() {
        List<Map<String, Object>> list = new ArrayList<>();
        String[] devices = {"大华摄像头", "大华人脸识别主机", "车间枪机", "接入交换机", "门禁控制器", "NVR-01", "核心服务器"};
        String[] sevs = {"critical", "critical", "warning", "warning", "info"};
        String[] types = {"device_offline", "device_line_abnormal", "device_recovered"};
        LocalDateTime base = LocalDateTime.now();
        for (int i = 0; i < 100; i++) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("id", 100001L + i);
            ev.put("severity", sevs[i % sevs.length]);
            ev.put("deviceName", devices[i % devices.length]);
            ev.put("eventType", types[i % types.length]);
            ev.put("createTime", base.minusMinutes(5L * (i + 1)).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            list.add(ev);
        }
        return list;
    }

    /** 演示模式：设备类型分布条目（online + offline） */
    private void addType(List<Map<String, Object>> list, String type, long online, long offline) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("deviceType", type);
        item.put("total", online + offline);
        item.put("online", online);
        item.put("offline", offline);
        list.add(item);
    }

    /** 演示模式：告警 TOP 设备条目 */
    private void addTop(List<Map<String, Object>> list, String name, long count) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("deviceName", name);
        item.put("count", count);
        list.add(item);
    }

    /** 演示模式：低库存备件条目 */
    private void addSpare(List<Map<String, Object>> list, String partNo, String partName, long quantity, long safeStock) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("partNo", partNo);
        item.put("partName", partName);
        item.put("quantity", quantity);
        item.put("safeStock", safeStock);
        list.add(item);
    }

    /** 演示模式：知识分类条目 */
    private void addCategory(List<Map<String, Object>> list, String category, long count) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("category", category);
        item.put("count", count);
        list.add(item);
    }

    /** 演示模式：公告条目 */
    private void addNotice(List<Map<String, Object>> list, String title, String level, int isTop, String publishTime) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("title", title);
        item.put("level", level);
        item.put("isTop", isTop);
        item.put("publishTime", publishTime);
        list.add(item);
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
