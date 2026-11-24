package com.netsight.gateway.localapi;

import com.netsight.gateway.core.ConfigHolder;
import com.netsight.gateway.sync.InventorySync;
import com.netsight.gateway.sync.LocalInventoryStore;
import com.netsight.gateway.sync.MappingDevice;
import com.netsight.gateway.sync.MappingSync;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class LocalDeviceService {

    private static final Set<String> TYPES = Set.of("network", "camera", "nvr", "door_controller");
    private static final String IPV4 = "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$";
    private static final String CLEAR_CONFIRM = "清空全部";

    private final ConfigHolder configHolder;
    private final LocalInventoryStore store;
    private final MappingSync mappingSync;
    private final InventorySync inventorySync;

    public List<MappingDevice> list() {
        return store.read().getDevices();
    }

    public MappingDevice create(LocalDeviceRequest req) {
        validate(req);
        String code = StringUtils.hasText(req.getDeviceCode()) ? req.getDeviceCode().trim() : nextCode();
        validateCode(code);
        if (find(code) != null) {
            throw new IllegalArgumentException("设备编码已存在: " + code);
        }
        MappingDevice device = toDevice(req, code);
        store.mutate(doc -> {
            doc.getDevices().add(device);
            return null;
        });
        refreshAndSync(false);
        return device;
    }

    public MappingDevice update(String deviceCode, LocalDeviceRequest req) {
        validate(req);
        MappingDevice exist = find(deviceCode);
        if (exist == null) {
            throw new IllegalArgumentException("设备不存在: " + deviceCode);
        }
        store.mutate(doc -> {
            MappingDevice target = doc.getDevices().stream()
                    .filter(d -> deviceCode.equals(d.getDeviceCode())).findFirst().orElseThrow();
            target.setDeviceName(req.getDeviceName().trim());
            target.setDeviceIp(req.getDeviceIp().trim());
            target.setDeviceType(req.getDeviceType());
            target.setLocation(trim(req.getLocation()));
            target.setBrand(trim(req.getBrand()));
            target.setModel(trim(req.getModel()));
            target.setWarrantyExpire(trim(req.getWarrantyExpire()));
            target.setCollectType("ping");
            target.setCollectPort(0);
            return null;
        });
        refreshAndSync(false);
        return find(deviceCode);
    }

    public void delete(String deviceCode, boolean confirmEmpty) {
        MappingDevice exist = find(deviceCode);
        if (exist == null) {
            throw new IllegalArgumentException("设备不存在: " + deviceCode);
        }
        boolean last = store.getDeviceCount() == 1;
        if (last && !confirmEmpty) {
            throw new IllegalArgumentException("删除最后一台设备需要在页面确认“清空全部”");
        }
        store.mutate(doc -> {
            doc.getDevices().removeIf(d -> deviceCode.equals(d.getDeviceCode()));
            return null;
        });
        refreshAndSync(last);
    }

    public void clearAll(String confirm) {
        if (!CLEAR_CONFIRM.equals(confirm)) {
            throw new IllegalArgumentException("确认短语不正确，必须输入“清空全部”");
        }
        store.mutate(doc -> {
            doc.getDevices().clear();
            return null;
        });
        refreshAndSync(true);
    }

    public String syncNow() {
        mappingSync.reloadLocal();
        inventorySync.triggerNow(false);
        return "已触发同步";
    }

    private void refreshAndSync(boolean allowEmpty) {
        mappingSync.reloadLocal();
        inventorySync.triggerNow(allowEmpty);
    }

    private MappingDevice find(String code) {
        return store.read().getDevices().stream()
                .filter(d -> code.equals(d.getDeviceCode()))
                .findFirst().orElse(null);
    }

    private String nextCode() {
        String gatewayCode = configHolder.getConfig().getGatewayCode();
        String prefix = "DEV-" + gatewayCode + "-";
        int max = 0;
        for (MappingDevice d : store.read().getDevices()) {
            String code = d.getDeviceCode();
            if (code != null && code.startsWith(prefix)) {
                try {
                    max = Math.max(max, Integer.parseInt(code.substring(prefix.length())));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return prefix + String.format("%04d", max + 1);
    }

    private void validateCode(String code) {
        if (!StringUtils.hasText(code) || code.length() > 64 || !code.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("设备编码只允许字母、数字、-、_，最大 64 字符");
        }
    }

    private MappingDevice toDevice(LocalDeviceRequest req, String code) {
        MappingDevice d = new MappingDevice();
        d.setDeviceCode(code);
        d.setDeviceName(req.getDeviceName().trim());
        d.setDeviceIp(req.getDeviceIp().trim());
        d.setDeviceType(req.getDeviceType());
        d.setLocation(trim(req.getLocation()));
        d.setBrand(trim(req.getBrand()));
        d.setModel(trim(req.getModel()));
        d.setWarrantyExpire(trim(req.getWarrantyExpire()));
        d.setCollectType("ping");
        d.setCollectPort(0);
        return d;
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static void validate(LocalDeviceRequest req) {
        if (!StringUtils.hasText(req.getDeviceName())) {
            throw new IllegalArgumentException("设备名称不能为空");
        }
        if (!StringUtils.hasText(req.getDeviceIp()) || !req.getDeviceIp().trim().matches(IPV4)) {
            throw new IllegalArgumentException("管理 IP 必须是合法 IPv4");
        }
        if (req.getDeviceType() == null || !TYPES.contains(req.getDeviceType())) {
            throw new IllegalArgumentException("设备类型不合法");
        }
        if (StringUtils.hasText(req.getWarrantyExpire())) {
            try {
                LocalDate.parse(req.getWarrantyExpire().trim());
            } catch (Exception e) {
                throw new IllegalArgumentException("保修截止日期格式必须为 YYYY-MM-DD");
            }
        }
    }
}
