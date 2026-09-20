package com.netsight.modules.system.controller;

import com.netsight.common.core.PageResult;
import com.netsight.common.core.R;
import com.netsight.modules.system.entity.Device;
import com.netsight.modules.system.service.DeviceService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import lombok.extern.slf4j.Slf4j;
import com.netsight.framework.aspectj.Log;

/**
 * 设备资产管理接口
 * /device：设备 CRUD、详情、拓扑维护、拓扑树
 */
@RestController
@RequestMapping("/device")
@RequiredArgsConstructor
@Slf4j
public class DeviceController {

    private final DeviceService deviceService;

    /**
     * 设备列表（分页）
     */
    @GetMapping("/list")
    @PreAuthorize("hasRole('super_admin') or hasRole('tenant_admin') or hasRole('ops')")
    public R<PageResult<Device>> list(@RequestParam(defaultValue = "1") long pageNum,
                                      @RequestParam(defaultValue = "10") long pageSize,
                                      @RequestParam(required = false) String deviceName,
                                      @RequestParam(required = false) String deviceType,
                                      @RequestParam(required = false) Integer status) {
        return R.ok(deviceService.pageDevice(pageNum, pageSize, deviceName, deviceType, status));
    }

    /**
     * 设备详情（含拓扑）
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('super_admin') or hasRole('tenant_admin') or hasRole('ops')")
    public R<Device> detail(@PathVariable Long id) {
        return R.ok(deviceService.getDeviceDetail(id));
    }

    /**
     * 设备二维码信息（V1.2.7 demo 5/5）
     * 返回设备精简信息（编码/名称/类型/IP/位置），前端据此生成二维码标签
     */
    @GetMapping("/qrcode/{id}")
    @PreAuthorize("hasRole('super_admin') or hasRole('tenant_admin') or hasRole('ops')")
    public R<java.util.Map<String, Object>> qrcode(@PathVariable Long id) {
        return R.ok(deviceService.getQrcodeInfo(id));
    }

    /**
     * 新增设备（含拓扑主备三路）
     */
    @PostMapping
    @PreAuthorize("hasRole('super_admin') or hasRole('tenant_admin')")
    @Log(module="设备管理", action="新增设备")
    public R<Void> add(@RequestBody Device device) {
        deviceService.addDevice(device);
        return R.ok();
    }

    /**
     * 修改设备（含拓扑更新）
     */
    @PutMapping
    @PreAuthorize("hasRole('super_admin') or hasRole('tenant_admin')")
    @Log(module="设备管理", action="修改设备")
    public R<Void> update(@RequestBody Device device) {
        deviceService.updateDevice(device);
        return R.ok();
    }

    /**
     * 删除设备（逻辑删除，级联清理拓扑）
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('super_admin') or hasRole('tenant_admin')")
    @Log(module="设备管理", action="删除设备")
    public R<Void> delete(@PathVariable Long id) {
        deviceService.deleteDevice(id);
        return R.ok();
    }

    /**
     * 拓扑树数据（节点 + 主备连线，拓扑图渲染用）
     */
    @GetMapping("/topology/tree")
    @PreAuthorize("hasRole('super_admin') or hasRole('tenant_admin') or hasRole('ops')")
    public R<java.util.Map<String, Object>> topologyTree() {
        return R.ok(deviceService.topologyTree());
    }
}
