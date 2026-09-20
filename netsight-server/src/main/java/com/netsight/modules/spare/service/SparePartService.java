package com.netsight.modules.spare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.netsight.common.core.PageResult;
import com.netsight.common.exception.ServiceException;
import com.netsight.framework.security.SecurityUtils;
import com.netsight.modules.alert.entity.EventRecord;
import com.netsight.modules.alert.service.EventCenterService;
import com.netsight.modules.spare.entity.SparePart;
import com.netsight.modules.spare.entity.SparePartRecord;
import com.netsight.modules.spare.mapper.SparePartMapper;
import com.netsight.modules.spare.mapper.SparePartRecordMapper;
import com.netsight.modules.workorder.entity.WorkOrder;
import com.netsight.modules.workorder.entity.WorkOrderPart;
import com.netsight.modules.workorder.mapper.WorkOrderMapper;
import com.netsight.modules.workorder.mapper.WorkOrderPartMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 备品备件服务（库存 + 出入库流水 + 工单绑定）
 * 出入库类型：in 入库 / out 领用出库（关联工单） / return_in 返修入库 / repair 返修 / scrap 报废 / adjust 调整
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SparePartService {

    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final SparePartMapper partMapper;
    private final SparePartRecordMapper recordMapper;
    private final WorkOrderPartMapper orderPartMapper;
    private final WorkOrderMapper workOrderMapper;
    private final EventCenterService eventCenterService;

    /**
     * 备件分页（低库存标记 lowStock）
     */
    public PageResult<SparePart> pagePart(long pageNum, long pageSize, String partType,
                                          String brand, String status, String keyword) {
        Page<SparePart> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<SparePart> wrapper = new LambdaQueryWrapper<SparePart>()
                .eq(StringUtils.hasText(partType), SparePart::getPartType, partType)
                .like(StringUtils.hasText(brand), SparePart::getBrand, brand)
                .eq(StringUtils.hasText(status), SparePart::getStatus, status)
                .and(StringUtils.hasText(keyword), w -> w
                        .like(SparePart::getPartNo, keyword)
                        .or().like(SparePart::getModel, keyword)
                        .or().like(SparePart::getSerialNo, keyword))
                .orderByDesc(SparePart::getId);
        Page<SparePart> result = partMapper.selectPage(page, wrapper);
        result.getRecords().forEach(p -> p.setLowStock(p.getQuantity() != null && p.getSafeStock() != null
                && p.getQuantity() <= p.getSafeStock()));
        return PageResult.of(result.getTotal(), result.getRecords());
    }

    /**
     * 备件类型统计（前端筛选下拉）
     */
    public List<SparePart> listTypes() {
        return partMapper.selectList(new LambdaQueryWrapper<SparePart>()
                .select(SparePart::getPartType).groupBy(SparePart::getPartType));
    }

    /**
     * 可用备件选项（工单领用弹窗，排除返修中）
     */
    public List<SparePart> listAvailable() {
        return partMapper.selectList(new LambdaQueryWrapper<SparePart>()
                .ne(SparePart::getStatus, "repairing").orderByAsc(SparePart::getId));
    }

    @Transactional(rollbackFor = Exception.class)
    public Long addPart(SparePart part) {
        part.setTenantId(SecurityUtils.getTenantId());
        part.setPartNo(genNo("SP"));
        if (part.getQuantity() == null) {
            part.setQuantity(0);
        }
        if (part.getStatus() == null) {
            part.setStatus("new");
        }
        if (part.getSafeStock() == null) {
            part.setSafeStock(0);
        }
        partMapper.insert(part);
        // 初始入库流水
        if (part.getQuantity() > 0) {
            addRecord(part, "in", part.getQuantity(), null, null,
                    "初始入库" + (StringUtils.hasText(part.getRemark()) ? "：" + part.getRemark() : ""));
        }
        // 初始即低库存预警（方案10.4）
        checkLowStockAndNotify(part);
        return part.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void updatePart(SparePart part) {
        checkTenantPart(part.getId());
        part.setPartNo(null);
        part.setTenantId(null);
        // 库存只能通过出入库操作变更，禁止直接覆盖
        part.setQuantity(null);
        partMapper.updateById(part);
    }

    @Transactional(rollbackFor = Exception.class)
    public void removePart(Long id) {
        checkTenantPart(id);
        partMapper.deleteById(id);
        // 历史流水保留（审计追溯），不级联删除
    }

    /**
     * 出入库操作：库存增减 + 流水落库 + 工单绑定（out 领用出库必填工单）
     *
     * @param partId    备件ID
     * @param recordType in/out/return_in/repair/scrap/adjust
     * @param quantity   数量（正数）
     * @param orderId    关联工单ID（out 必填）
     * @param orderNo    工单编号
     * @param remark     备注
     */
    @Transactional(rollbackFor = Exception.class)
    public void stock(Long partId, String recordType, Integer quantity, Long orderId, String orderNo, String remark) {
        SparePart part = checkTenantPart(partId);
        if (quantity == null || quantity <= 0) {
            throw new ServiceException(400, "数量必须大于0");
        }
        String type = recordType == null ? "" : recordType;
        int delta;
        switch (type) {
            case "in", "return_in", "adjust" -> delta = quantity;
            case "out", "scrap" -> delta = -quantity;
            case "repair" -> delta = 0; // 状态流转，库存不变
            default -> throw new ServiceException(400, "不支持的出入库类型: " + recordType);
        }
        // 领用出库必须绑定工单（方案10.3）
        if ("out".equals(type) && orderId == null) {
            throw new ServiceException(400, "领用出库必须关联工单");
        }
        // 【加固】领用出库的工单必须与备件同租户：防止跨租户将本租户备件绑定到其他租户工单（数据错乱 + 库存串用）
        if ("out".equals(type) && orderId != null) {
            WorkOrder wo = workOrderMapper.selectById(orderId);
            if (wo == null || !part.getTenantId().equals(wo.getTenantId())) {
                throw new ServiceException(400, "关联工单不存在或不属于当前租户");
            }
        }
        // 【并发加固】库存变更改用原子条件 UPDATE（替代"先读后写"，杜绝并发领用丢失更新）：
        // 扣减类（out/scrap）带 quantity>=N 条件，受影响行数 0 即并发下库存不足；
        // 多租户下 TenantLine 拦截器自动附加 tenant_id 条件，跨租户天然隔离。
        LambdaUpdateWrapper<SparePart> updateWrapper = new LambdaUpdateWrapper<SparePart>()
                .eq(SparePart::getId, partId);
        if (delta < 0) {
            updateWrapper.ge(SparePart::getQuantity, -delta);
        }
        updateWrapper.setSql("quantity = quantity + " + delta);
        if ("repair".equals(type)) {
            updateWrapper.set(SparePart::getStatus, "repairing");
        } else if ("return_in".equals(type)) {
            updateWrapper.set(SparePart::getStatus, "repaired");
        }
        int rows = partMapper.update(null, updateWrapper);
        if (rows == 0) {
            // 并发下库存被扣光：重查一次给出准确提示
            SparePart latest = partMapper.selectById(partId);
            throw new ServiceException(500, "库存不足，当前库存 "
                    + (latest == null ? 0 : latest.getQuantity()) + " "
                    + (latest == null ? "" : latest.getUnit()));
        }
        // 重新读取最新库存与状态（供流水/低库存预警使用，避免使用竞态前的旧值）
        SparePart latest = partMapper.selectById(partId);
        if (latest != null) {
            part.setQuantity(latest.getQuantity());
            if (latest.getStatus() != null) {
                part.setStatus(latest.getStatus());
            }
        }
        // 流水
        SparePartRecord record = addRecord(part, type, delta == 0 ? quantity : delta, orderId, orderNo, remark);
        // 工单备件绑定（领用出库）
        if ("out".equals(recordType) && orderId != null) {
            WorkOrderPart op = new WorkOrderPart();
            op.setTenantId(part.getTenantId());
            op.setOrderId(orderId);
            op.setPartId(part.getId());
            op.setPartNo(part.getPartNo());
            op.setPartName(partName(part));
            op.setQuantity(quantity);
            op.setUnit(part.getUnit());
            orderPartMapper.insert(op);
            log.info("备件[{}]领用 {} 件绑定工单[{}]", part.getPartNo(), quantity, orderNo);
        }
        // 出入库后低库存预警（方案10.4：低于安全库存自动提醒）
        checkLowStockAndNotify(part);
    }

    /**
     * 低库存预警：安全库存>0 且 当前库存<=安全库存 时，向事件中心发送 stock_low 事件
     * （事件中心按通知规则路由：短信+公众号，5 分钟去重）
     */
    private void checkLowStockAndNotify(SparePart part) {
        try {
            Integer safeStock = part.getSafeStock();
            Integer quantity = part.getQuantity();
            if (safeStock == null || safeStock <= 0 || quantity == null || quantity > safeStock) {
                return;
            }
            EventRecord event = new EventRecord();
            event.setTenantId(part.getTenantId());
            event.setEventType("stock_low");
            event.setEventSource("spare");
            event.setSeverity("warning");
            // 按天去重：同一天同备件只提醒一次（事件中心5分钟去重基础上叠加）
            event.setBizId("stock_low:" + part.getId() + ":" + LocalDate.now());
            event.setDeviceName(partName(part));
            event.setDeviceIp(part.getLocation());
            event.setDeviceType("spare_part");
            event.setLocation(part.getLocation());
            event.setContent("备件[" + part.getPartNo() + "]当前库存 " + quantity + " " + part.getUnit()
                    + "，低于安全库存 " + safeStock + " " + part.getUnit() + "，请及时补货");
            event.setRuleName("备件库存预警");
            // 模板渲染变量（labels 合并进 buildVars）
            try {
                Map<String, Object> labels = new HashMap<>();
                labels.put("partName", partName(part));
                labels.put("partNo", part.getPartNo());
                labels.put("partType", part.getPartType() == null ? "" : part.getPartType());
                labels.put("quantity", quantity);
                labels.put("unit", part.getUnit() == null ? "" : part.getUnit());
                labels.put("safeStock", safeStock);
                labels.put("location", part.getLocation() == null ? "" : part.getLocation());
                event.setLabelsJson(OBJECT_MAPPER.writeValueAsString(labels));
            } catch (Exception ignored) {
                // 变量序列化失败不影响事件主流程
            }
            eventCenterService.receiveEvent(event);
            log.info("备件[{}]低库存预警触发：库存 {} <= 安全库存 {}", part.getPartNo(), quantity, safeStock);
        } catch (Exception e) {
            // 预警失败不影响主流程
            log.error("备件低库存预警异常 partId={}: {}", part.getId(), e.getMessage());
        }
    }

    /**
     * 出入库记录分页（按备件/类型/工单筛选）
     */
    public PageResult<SparePartRecord> pageRecord(long pageNum, long pageSize, String partNo,
                                                  String recordType, String orderNo) {
        Page<SparePartRecord> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<SparePartRecord> wrapper = new LambdaQueryWrapper<SparePartRecord>()
                .like(StringUtils.hasText(partNo), SparePartRecord::getPartNo, partNo)
                .eq(StringUtils.hasText(recordType), SparePartRecord::getRecordType, recordType)
                .like(StringUtils.hasText(orderNo), SparePartRecord::getOrderNo, orderNo)
                .orderByDesc(SparePartRecord::getId);
        Page<SparePartRecord> result = recordMapper.selectPage(page, wrapper);
        return PageResult.of(result.getTotal(), result.getRecords());
    }

    /**
     * 备件统计（顶部卡片）
     */
    public Map<String, Object> stats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("total", partMapper.selectCount(new LambdaQueryWrapper<>()));
        stats.put("lowStock", partMapper.selectCount(new LambdaQueryWrapper<SparePart>()
                .apply("quantity <= safe_stock")));
        stats.put("repairing", partMapper.selectCount(new LambdaQueryWrapper<SparePart>()
                .eq(SparePart::getStatus, "repairing")));
        stats.put("stockTotal", partMapper.selectObjs(new LambdaQueryWrapper<SparePart>()
                        .select(SparePart::getQuantity))
                .stream().mapToInt(o -> o == null ? 0 : ((Number) o).intValue()).sum());
        return stats;
    }

    // ==================== 内部 ====================

    /**
     * 备件存在性 + 数据隔离校验（显式校验：selectById 依赖 TenantLine 拦截器，
     * 在超管或无租户上下文时不过滤，必须手工按当前租户校验，防跨租户操作）
     */
    private SparePart checkTenantPart(Long id) {
        SparePart part = partMapper.selectById(id);
        if (part == null) {
            throw new ServiceException(500, "备件不存在");
        }
        if (!SecurityUtils.isSuperAdmin()
                && (part.getTenantId() == null || !part.getTenantId().equals(SecurityUtils.getTenantId()))) {
            throw new ServiceException(403, "无权操作其他租户的备件");
        }
        return part;
    }

    private SparePartRecord addRecord(SparePart part, String recordType, Integer quantity,
                                      Long orderId, String orderNo, String remark) {
        SparePartRecord record = new SparePartRecord();
        record.setTenantId(part.getTenantId());
        record.setPartId(part.getId());
        record.setPartNo(part.getPartNo());
        record.setPartName(partName(part));
        record.setRecordType(recordType);
        record.setQuantity(quantity);
        record.setOrderId(orderId);
        record.setOrderNo(orderNo);
        record.setOperator(currentUsername());
        record.setRemark(remark);
        recordMapper.insert(record);
        return record;
    }

    private String partName(SparePart part) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(part.getPartType())) {
            sb.append(part.getPartType());
        }
        if (StringUtils.hasText(part.getBrand())) {
            sb.append(' ').append(part.getBrand());
        }
        if (StringUtils.hasText(part.getModel())) {
            sb.append(' ').append(part.getModel());
        }
        return sb.length() == 0 ? part.getPartNo() : sb.toString();
    }

    private String genNo(String prefix) {
        return prefix + LocalDateTime.now().format(NO_FMT) + ThreadLocalRandom.current().nextInt(1000, 10000);
    }

    /** 当前登录用户名（未登录返回系统） */
    private String currentUsername() {
        try {
            if (SecurityUtils.isAuthenticated() && SecurityUtils.getLoginUser() != null
                    && StringUtils.hasText(SecurityUtils.getLoginUser().getUsername())) {
                return SecurityUtils.getLoginUser().getUsername();
            }
        } catch (Exception ignored) {
        }
        return "系统";
    }
}
