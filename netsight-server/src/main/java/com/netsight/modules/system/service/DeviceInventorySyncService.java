package com.netsight.modules.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.netsight.common.exception.ServiceException;
import com.netsight.common.exception.StaleRevisionException;
import com.netsight.modules.system.dto.DeviceInventorySyncRequest;
import com.netsight.modules.system.dto.DeviceInventorySyncResult;
import com.netsight.modules.system.entity.Device;
import com.netsight.modules.system.entity.EdgeGateway;
import com.netsight.modules.system.mapper.DeviceMapper;
import com.netsight.modules.system.mapper.EdgeGatewayMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceInventorySyncService {

    private static final String IPV4 = "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$";
    private static final Set<String> TYPES = Set.of("network", "camera", "nvr", "door_controller");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final EdgeGatewayMapper edgeGatewayMapper;
    private final DeviceMapper deviceMapper;

    @Transactional(rollbackFor = Exception.class)
    public DeviceInventorySyncResult sync(EdgeGateway gateway, DeviceInventorySyncRequest req) {
        if (req == null || req.getRevision() == null) {
            throw new ServiceException(400, "revision 不能为空");
        }
        if (!Objects.equals(gateway.getGatewayCode(), req.getGatewayCode())) {
            throw new ServiceException(403, "gatewayCode 与 Token 不匹配");
        }
        long current = gateway.getInventoryRevision() == null ? 0L : gateway.getInventoryRevision();
        if (req.getRevision() < current) {
            throw new StaleRevisionException(current);
        }
        List<DeviceInventorySyncRequest.Item> items = req.getDevices() == null ? List.of() : req.getDevices();
        if (items.isEmpty() && !Boolean.TRUE.equals(req.getAllowEmpty())) {
            throw new ServiceException(400, "空设备清单被拒绝；如确需清空，请在本地页面二次确认");
        }
        Set<String> codes = validateAndCollect(items);

        int created = 0;
        int updated = 0;
        int unchanged = 0;
        LocalDateTime now = LocalDateTime.now();

        for (DeviceInventorySyncRequest.Item item : items) {
            Device existing = deviceMapper.selectByGatewayAndCodeIncludingDeleted(gateway.getId(), item.getDeviceCode());
            if (existing == null) {
                deviceMapper.insert(toNewDevice(gateway, item, now));
                created++;
                continue;
            }
            boolean restored = existing.getDelFlag() != null && existing.getDelFlag() == 2;
            if (restored) {
                deviceMapper.restoreById(existing.getId());
            }
            Device update = toUpdateDevice(gateway, existing.getId(), item, now);
            if (restored || !sameConfig(existing, update)) {
                updateById(update);
                updated++;
            } else {
                unchanged++;
            }
        }

        int disabled = 0;
        if (Boolean.TRUE.equals(req.getFull())) {
            List<Device> active = deviceMapper.selectList(new LambdaQueryWrapper<Device>()
                    .eq(Device::getGatewayId, gateway.getId()));
            for (Device d : active) {
                if (!codes.contains(d.getDeviceCode())) {
                    deviceMapper.deleteById(d.getId());
                    disabled++;
                }
            }
        }

        EdgeGateway updateGateway = new EdgeGateway();
        updateGateway.setId(gateway.getId());
        updateGateway.setInventoryRevision(req.getRevision());
        updateGateway.setInventorySyncedAt(now);
        updateGateway.setInventoryDeviceCount(items.size());
        edgeGatewayMapper.updateById(updateGateway);

        String syncTime = now.format(TS);
        log.info("网关[{}]设备清单同步完成: revision={}, created={}, updated={}, disabled={}, unchanged={}",
                gateway.getGatewayCode(), req.getRevision(), created, updated, disabled, unchanged);
        return new DeviceInventorySyncResult(req.getRevision(), created, updated, disabled, unchanged, syncTime);
    }

    private Set<String> validateAndCollect(List<DeviceInventorySyncRequest.Item> items) {
        Set<String> codes = new HashSet<>();
        for (DeviceInventorySyncRequest.Item item : items) {
            if (!StringUtils.hasText(item.getDeviceCode()) || item.getDeviceCode().length() > 64
                    || !item.getDeviceCode().matches("[A-Za-z0-9_-]+")) {
                throw new ServiceException(400, "deviceCode 不合法: " + item.getDeviceCode());
            }
            if (!codes.add(item.getDeviceCode())) {
                throw new ServiceException(400, "deviceCode 重复: " + item.getDeviceCode());
            }
            if (!StringUtils.hasText(item.getDeviceName())) {
                throw new ServiceException(400, "deviceName 不能为空: " + item.getDeviceCode());
            }
            if (!StringUtils.hasText(item.getDeviceIp()) || !item.getDeviceIp().trim().matches(IPV4)) {
                throw new ServiceException(400, "deviceIp 不是合法 IPv4: " + item.getDeviceCode());
            }
            if (item.getDeviceType() == null || !TYPES.contains(item.getDeviceType())) {
                throw new ServiceException(400, "deviceType 不合法: " + item.getDeviceCode());
            }
            if (StringUtils.hasText(item.getWarrantyExpire())) {
                try {
                    LocalDate.parse(item.getWarrantyExpire().trim());
                } catch (Exception e) {
                    throw new ServiceException(400, "warrantyExpire 格式错误: " + item.getDeviceCode());
                }
            }
        }
        return codes;
    }

    private Device toNewDevice(EdgeGateway gateway, DeviceInventorySyncRequest.Item item, LocalDateTime now) {
        Device d = new Device();
        fill(d, gateway, item, now);
        return d;
    }

    private Device toUpdateDevice(EdgeGateway gateway, Long id, DeviceInventorySyncRequest.Item item, LocalDateTime now) {
        Device d = new Device();
        d.setId(id);
        fill(d, gateway, item, now);
        return d;
    }

    private void fill(Device d, EdgeGateway gateway, DeviceInventorySyncRequest.Item item, LocalDateTime now) {
        d.setDeviceCode(item.getDeviceCode());
        d.setDeviceName(item.getDeviceName().trim());
        d.setDeviceType(item.getDeviceType());
        d.setBrand(trim(item.getBrand()));
        d.setModel(trim(item.getModel()));
        d.setIpAddress(item.getDeviceIp().trim());
        d.setLocation(trim(item.getLocation()));
        d.setGatewayId(gateway.getId());
        d.setTenantId(gateway.getTenantId());
        d.setCollectType("ping");
        d.setCollectPort(0);
        d.setWarrantyExpire(StringUtils.hasText(item.getWarrantyExpire())
                ? LocalDate.parse(item.getWarrantyExpire().trim()) : null);
        d.setSource("edge");
        d.setSyncedAt(now);
    }

    /**
     * 全字段更新，保证网关侧清空品牌/型号/位置/保修时云端同步清空。
     */
    private void updateById(Device d) {
        LambdaUpdateWrapper<Device> uw = new LambdaUpdateWrapper<>();
        uw.eq(Device::getId, d.getId())
                .set(Device::getDeviceCode, d.getDeviceCode())
                .set(Device::getDeviceName, d.getDeviceName())
                .set(Device::getDeviceType, d.getDeviceType())
                .set(Device::getBrand, d.getBrand())
                .set(Device::getModel, d.getModel())
                .set(Device::getIpAddress, d.getIpAddress())
                .set(Device::getLocation, d.getLocation())
                .set(Device::getGatewayId, d.getGatewayId())
                .set(Device::getTenantId, d.getTenantId())
                .set(Device::getCollectType, d.getCollectType())
                .set(Device::getCollectPort, d.getCollectPort())
                .set(Device::getWarrantyExpire, d.getWarrantyExpire())
                .set(Device::getSource, d.getSource())
                .set(Device::getSyncedAt, d.getSyncedAt())
                .set(Device::getUpdateTime, d.getSyncedAt());
        deviceMapper.update(null, uw);
    }

    private boolean sameConfig(Device a, Device b) {
        return Objects.equals(a.getDeviceName(), b.getDeviceName())
                && Objects.equals(a.getDeviceType(), b.getDeviceType())
                && Objects.equals(a.getBrand(), b.getBrand())
                && Objects.equals(a.getModel(), b.getModel())
                && Objects.equals(a.getIpAddress(), b.getIpAddress())
                && Objects.equals(a.getLocation(), b.getLocation())
                && Objects.equals(a.getWarrantyExpire(), b.getWarrantyExpire())
                && Objects.equals(a.getCollectType(), b.getCollectType())
                && Objects.equals(a.getCollectPort(), b.getCollectPort())
                && Objects.equals(a.getSource(), b.getSource());
    }

    private String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
