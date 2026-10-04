package com.netsight.modules.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.netsight.common.core.PageResult;
import com.netsight.common.core.ResultCode;
import com.netsight.common.exception.ServiceException;
import com.netsight.framework.security.SecurityUtils;
import com.netsight.modules.system.entity.Device;
import com.netsight.modules.system.entity.DeviceTopology;
import com.netsight.modules.system.entity.EdgeGateway;
import com.netsight.modules.system.mapper.DeviceMapper;
import com.netsight.modules.system.mapper.DeviceTopologyMapper;
import com.netsight.modules.system.mapper.EdgeGatewayMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 设备资产管理服务
 * 设备 CRUD、租户隔离、归属网关、拓扑关系（主备三路）维护
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceService {

    private final DeviceMapper deviceMapper;
    private final EdgeGatewayMapper edgeGatewayMapper;
    private final DeviceTopologyMapper deviceTopologyMapper;

    /**
     * 分页查询设备（租户隔离；列表回显网关名与拓扑上级名）
     */
    public PageResult<Device> pageDevice(long pageNum, long pageSize, String deviceName, String deviceType, Integer status) {
        Page<Device> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<Device> wrapper = new LambdaQueryWrapper<Device>()
                .like(StringUtils.hasText(deviceName), Device::getDeviceName, deviceName)
                .eq(StringUtils.hasText(deviceType), Device::getDeviceType, deviceType)
                .eq(status != null, Device::getStatus, status);
        if (!SecurityUtils.isSuperAdmin()) {
            wrapper.eq(Device::getTenantId, SecurityUtils.getTenantId());
        }
        wrapper.orderByDesc(Device::getId);
        Page<Device> result = deviceMapper.selectPage(page, wrapper);
        fillDisplayFields(result.getRecords());
        return PageResult.of(result.getTotal(), result.getRecords());
    }

    /**
     * 新增设备：自动生成设备编码，保存拓扑（主备三路）
     */
    @Transactional(rollbackFor = Exception.class)
    public void addDevice(Device device) {
        // 租户归属：超管可指定，普通管理员归本租户
        if (SecurityUtils.isSuperAdmin() && device.getTenantId() != null) {
            // 使用指定租户
        } else {
            device.setTenantId(SecurityUtils.getTenantId());
        }
        // 设备编码唯一
        if (device.getDeviceCode() == null || device.getDeviceCode().isBlank()) {
            device.setDeviceCode("DEV" + System.currentTimeMillis() + randomSuffix());
        }
        Long count = deviceMapper.selectCount(new LambdaQueryWrapper<Device>()
                .eq(Device::getDeviceCode, device.getDeviceCode()));
        if (count > 0) {
            throw new ServiceException(ResultCode.DEVICE_CODE_EXIST);
        }
        if (device.getStatus() == null) {
            device.setStatus(0);
        }
        if (device.getLineStatus() == null) {
            device.setLineStatus(0);
        }
        if (device.getCollectType() == null || device.getCollectType().isBlank()) {
            device.setCollectType("snmp");
        }
        if (device.getCollectPort() == null || device.getCollectPort() <= 0) {
            device.setCollectPort(161);
        }
        deviceMapper.insert(device);
        saveTopology(device);
        log.info("新增设备: {} ({})，类型: {}，租户: {}", device.getDeviceName(), device.getDeviceCode(),
                device.getDeviceType(), device.getTenantId());
    }

    /**
     * 修改设备（含拓扑更新）
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateDevice(Device device) {
        Device exist = deviceMapper.selectById(device.getId());
        if (exist == null) {
            throw new ServiceException(ResultCode.DEVICE_NOT_FOUND);
        }
        checkTenantPermission(exist.getTenantId());
        // 设备编码唯一（排除自己）
        if (device.getDeviceCode() != null && !device.getDeviceCode().isBlank()) {
            Long count = deviceMapper.selectCount(new LambdaQueryWrapper<Device>()
                    .eq(Device::getDeviceCode, device.getDeviceCode())
                    .ne(Device::getId, device.getId()));
            if (count > 0) {
                throw new ServiceException(ResultCode.DEVICE_CODE_EXIST);
            }
        }
        Device update = new Device();
        update.setId(device.getId());
        update.setDeviceCode(device.getDeviceCode());
        update.setDeviceName(device.getDeviceName());
        update.setDeviceType(device.getDeviceType());
        update.setBrand(device.getBrand());
        update.setModel(device.getModel());
        update.setIpAddress(device.getIpAddress());
        update.setLocation(device.getLocation());
        update.setGatewayId(device.getGatewayId());
        update.setStatus(device.getStatus());
        update.setLineStatus(device.getLineStatus());
        // 保修截止日期：清空标记优先（null 写入需绕过 MP 默认 NOT_NULL 更新策略）；
        // 否则显式传入才更新（null 保持原值不覆盖）
        if (Boolean.TRUE.equals(device.getClearWarranty())) {
            com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Device> uw =
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<>();
            uw.eq(Device::getId, device.getId()).set(Device::getWarrantyExpire, null);
            deviceMapper.update(null, uw);
        } else if (device.getWarrantyExpire() != null) {
            update.setWarrantyExpire(device.getWarrantyExpire());
        }
        // 超管可调整设备归属租户（设备移入其他租户管理；网关需同步调整，否则旧网关采集的
        // 状态上报仍按 deviceCode 更新——跨租户场景请同时把设备绑定到目标租户的网关）
        if (SecurityUtils.isSuperAdmin() && device.getTenantId() != null) {
            update.setTenantId(device.getTenantId());
        }
        // 采集方式/端口（null 保持原值不覆盖；显式传入才更新）
        if (device.getCollectType() != null && !device.getCollectType().isBlank()) {
            update.setCollectType(device.getCollectType());
        }
        if (device.getCollectPort() != null && device.getCollectPort() > 0) {
            update.setCollectPort(device.getCollectPort());
        }
        deviceMapper.updateById(update);
        saveTopology(device);
    }

    /**
     * 删除设备（逻辑删除，同时删除拓扑）
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteDevice(Long id) {
        Device exist = deviceMapper.selectById(id);
        if (exist == null) {
            throw new ServiceException(ResultCode.DEVICE_NOT_FOUND);
        }
        checkTenantPermission(exist.getTenantId());
        deviceMapper.deleteById(id);
        // 删除自身拓扑 + 作为上级被引用的关系（物理删除，避免逻辑删除残留占唯一键）
        deviceTopologyMapper.physicalDeleteByDeviceId(id);
        deviceTopologyMapper.physicalDeleteByParentRef(id);
    }

    /**
     * 设备详情（含拓扑）
     */
    public Device getDeviceDetail(Long id) {
        Device device = deviceMapper.selectById(id);
        if (device == null) {
            throw new ServiceException(ResultCode.DEVICE_NOT_FOUND);
        }
        checkTenantPermission(device.getTenantId());
        fillDisplayFields(Collections.singletonList(device));
        return device;
    }

    /**
     * 设备二维码信息（V1.2.7 demo 5/5）
     * 返回设备精简信息供前端编码生成二维码标签：
     *  - 仅含设备识别字段（编码/名称/类型/IP/位置），不含租户信息，避免贴标泄露租户归属
     *  - 跨租户防探：checkTenantPermission 拦截非本租户设备
     *  - 后续小程序/APP 扫码后凭 deviceCode 走登录态接口查详情，二维码本身不做公开查询入口
     */
    public Map<String, Object> getQrcodeInfo(Long id) {
        Device device = deviceMapper.selectById(id);
        if (device == null) {
            throw new ServiceException(ResultCode.DEVICE_NOT_FOUND);
        }
        checkTenantPermission(device.getTenantId());
        Map<String, Object> info = new HashMap<>();
        info.put("deviceCode", device.getDeviceCode());
        info.put("deviceName", device.getDeviceName());
        info.put("deviceType", device.getDeviceType());
        info.put("ipAddress", device.getIpAddress());
        info.put("location", device.getLocation());
        return info;
    }

    /**
     * 保存设备拓扑（物理删除旧关系后重建，主备三路）
     * 注意：不能用 BaseMapper.delete()（逻辑删除会残留物理行，撞唯一键 uk_topology_device），
     * 必须 physicalDeleteByDeviceId 物理清除后 insert。
     */
    private void saveTopology(Device device) {
        // 先物理清理旧拓扑（含历史逻辑删除残留行）：表单提交即重建（主上级为空 = 删除该设备全部拓扑关系）
        deviceTopologyMapper.physicalDeleteByDeviceId(device.getId());
        if (device.getParentMainId() == null) {
            return;
        }
        DeviceTopology topology = new DeviceTopology();
        topology.setDeviceId(device.getId());
        topology.setParentMainId(device.getParentMainId());
        topology.setParentBackup1Id(device.getParentBackup1Id());
        topology.setParentBackup2Id(device.getParentBackup2Id());
        Long tenantId = device.getTenantId() != null ? device.getTenantId() : SecurityUtils.getTenantId();
        topology.setTenantId(tenantId);
        deviceTopologyMapper.insert(topology);
    }

    /**
     * 回显字段：网关名称、上级设备名称
     */
    private void fillDisplayFields(List<Device> devices) {
        if (devices == null || devices.isEmpty()) {
            return;
        }
        // 批量查网关名称（避免循环内查库）
        List<Long> gatewayIds = devices.stream()
                .map(Device::getGatewayId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, String> gatewayNames = gatewayIds.isEmpty() ? Map.of()
                : edgeGatewayMapper.selectBatchIds(gatewayIds).stream()
                .collect(Collectors.toMap(EdgeGateway::getId, EdgeGateway::getGatewayName));
        devices.forEach(d -> d.setGatewayName(gatewayNames.get(d.getGatewayId())));

        // 批量查拓扑上级
        List<Long> deviceIds = devices.stream().map(Device::getId).collect(Collectors.toList());
        List<DeviceTopology> topologies = deviceIds.isEmpty() ? List.of()
                : deviceTopologyMapper.selectList(new LambdaQueryWrapper<DeviceTopology>()
                .in(DeviceTopology::getDeviceId, deviceIds));
        Map<Long, DeviceTopology> topoMap = topologies.stream()
                .collect(Collectors.toMap(DeviceTopology::getDeviceId, Function.identity()));
        // 收集所有被引用的上级设备ID，一次性查名称
        List<Long> parentIds = topologies.stream()
                .flatMap(t -> java.util.stream.Stream.of(t.getParentMainId(), t.getParentBackup1Id(), t.getParentBackup2Id()))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, String> parentNames = parentIds.isEmpty() ? Map.of()
                : deviceMapper.selectBatchIds(parentIds).stream()
                .collect(Collectors.toMap(Device::getId, Device::getDeviceName));

        for (Device d : devices) {
            // 保修状态派生：null=未设置 / 截止日期>=今天=在保 / 截止日期<今天=已过保
            if (d.getWarrantyExpire() == null) {
                d.setWarrantyStatus("none");
            } else if (!d.getWarrantyExpire().isBefore(java.time.LocalDate.now())) {
                d.setWarrantyStatus("in_warranty");
            } else {
                d.setWarrantyStatus("expired");
            }
            DeviceTopology topo = topoMap.get(d.getId());
            if (topo == null) {
                continue;
            }
            d.setParentMainId(topo.getParentMainId());
            d.setParentBackup1Id(topo.getParentBackup1Id());
            d.setParentBackup2Id(topo.getParentBackup2Id());
            List<String> names = new ArrayList<>();
            if (topo.getParentMainId() != null) {
                names.add("主:" + parentNames.getOrDefault(topo.getParentMainId(), "-"));
            }
            if (topo.getParentBackup1Id() != null) {
                names.add("备1:" + parentNames.getOrDefault(topo.getParentBackup1Id(), "-"));
            }
            if (topo.getParentBackup2Id() != null) {
                names.add("备2:" + parentNames.getOrDefault(topo.getParentBackup2Id(), "-"));
            }
            d.setParentNames(names);
        }
    }

    /**
     * 拓扑树数据：返回全部可见设备节点与主备连线（前端拓扑图渲染用）
     * 节点包含 id/name/status/lineStatus；连线包含 from/to/lineType
     */
    public Map<String, Object> topologyTree() {
        List<Device> devices = listVisibleDevices();
        Map<Long, Device> deviceMap = devices.stream()
                .collect(Collectors.toMap(Device::getId, Function.identity()));
        // 节点
        List<Map<String, Object>> nodes = devices.stream().map(d -> {
            Map<String, Object> node = new java.util.HashMap<>();
            node.put("id", d.getId());
            node.put("name", d.getDeviceName());
            node.put("deviceType", d.getDeviceType());
            node.put("status", d.getStatus());
            node.put("lineStatus", d.getLineStatus());
            return node;
        }).collect(Collectors.toList());
        // 连线（主备三路）
        List<Map<String, Object>> links = new ArrayList<>();
        List<DeviceTopology> topologies = deviceTopologyMapper.selectList(new LambdaQueryWrapper<DeviceTopology>()
                .in(!deviceMap.isEmpty(), DeviceTopology::getDeviceId, deviceMap.keySet()));
        for (DeviceTopology t : topologies) {
            addLink(links, t.getParentMainId(), t.getDeviceId(), "main");
            addLink(links, t.getParentBackup1Id(), t.getDeviceId(), "backup1");
            addLink(links, t.getParentBackup2Id(), t.getDeviceId(), "backup2");
        }
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("nodes", nodes);
        result.put("links", links);
        return result;
    }

    private void addLink(List<Map<String, Object>> links, Long from, Long to, String lineType) {
        if (from == null || to == null) {
            return;
        }
        Map<String, Object> link = new java.util.HashMap<>();
        link.put("from", from);
        link.put("to", to);
        link.put("lineType", lineType);
        links.add(link);
    }

    /** 查询当前用户可见设备（拓扑树用） */
    private List<Device> listVisibleDevices() {
        LambdaQueryWrapper<Device> wrapper = new LambdaQueryWrapper<>();
        if (!SecurityUtils.isSuperAdmin()) {
            wrapper.eq(Device::getTenantId, SecurityUtils.getTenantId());
        }
        wrapper.orderByAsc(Device::getId);
        return deviceMapper.selectList(wrapper);
    }

    /**
     * 状态上报：批量更新设备运行状态与外线状态（网关上报，已鉴权）
     * <p>
     * 【安全加固】必须携带 gatewayId，更新条件同时约束 device_code + gateway_id：
     * 设备不存在或不属于该网关时 UPDATE 影响行数为 0（自然忽略），
     * 从根上杜绝"持任一租户网关 Token 篡改其他租户设备状态"的跨租户漏洞。
     * 单条条件 UPDATE 原子完成"归属校验 + 状态更新"，无先查后更的竞态窗口。
     * </p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateStatusBatch(List<Device> reports, Long gatewayId) {
        if (reports == null || reports.isEmpty()) {
            return;
        }
        if (gatewayId == null) {
            log.warn("updateStatusBatch 缺少 gatewayId，拒绝更新");
            return;
        }
        int updated = 0;
        for (Device report : reports) {
            if (!StringUtils.hasText(report.getDeviceCode())) {
                continue;
            }
            updated += deviceMapper.update(null, new LambdaUpdateWrapper<Device>()
                    .eq(Device::getGatewayId, gatewayId)
                    .eq(Device::getDeviceCode, report.getDeviceCode())
                    .set(Device::getStatus, report.getStatus())
                    .set(Device::getLineStatus, report.getLineStatus()));
        }
        if (updated > 0) {
            log.info("网关[{}]状态批量更新完成，归属本网关的更新 {} 台", gatewayId, updated);
        }
    }

    /**
     * 查询指定网关下的全部设备（云端清单下发：mapping 接口用）
     * 不含租户过滤——调用方已按网关 Token 鉴权，网关本身绑定租户，天然隔离。
     */
    public List<Device> listByGateway(Long gatewayId) {
        if (gatewayId == null) {
            return Collections.emptyList();
        }
        return deviceMapper.selectList(new LambdaQueryWrapper<Device>()
                .eq(Device::getGatewayId, gatewayId)
                .eq(Device::getDelFlag, 0)
                .orderByAsc(Device::getId));
    }

    /**
     * 数据隔离校验：非超管不能操作其他租户设备
     */
    private void checkTenantPermission(Long targetTenantId) {        if (!SecurityUtils.isSuperAdmin() && !targetTenantId.equals(SecurityUtils.getTenantId())) {
            throw new ServiceException(ResultCode.FORBIDDEN);
        }
    }

    /** 编码随机后缀（4位，ThreadLocalRandom 线程安全且避免可预测编码） */
    private String randomSuffix() {
        return String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }
}
