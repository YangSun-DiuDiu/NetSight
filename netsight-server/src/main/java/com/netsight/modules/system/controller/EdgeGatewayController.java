package com.netsight.modules.system.controller;

import com.netsight.common.core.PageResult;
import com.netsight.common.core.R;
import com.netsight.modules.system.entity.EdgeGateway;
import com.netsight.modules.system.service.EdgeGatewayService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import com.netsight.framework.aspectj.Log;

/**
 * 边缘网关管理接口
 * /edge/gateway：网关注册、编辑、删除、重置Token、状态查询
 */
@RestController
@RequestMapping("/edge/gateway")
@RequiredArgsConstructor
@Slf4j
public class EdgeGatewayController {

    private final EdgeGatewayService edgeGatewayService;

    /**
     * 网关列表（分页）
     */
    @GetMapping("/list")
    @PreAuthorize("hasRole('super_admin') or hasRole('tenant_admin')")
    public R<PageResult<EdgeGateway>> list(@RequestParam(defaultValue = "1") long pageNum,
                                           @RequestParam(defaultValue = "10") long pageSize,
                                           @RequestParam(required = false) String gatewayName,
                                           @RequestParam(required = false) Integer onlineStatus) {
        return R.ok(edgeGatewayService.pageGateway(pageNum, pageSize, gatewayName, onlineStatus));
    }

    /**
     * 网关下拉选项（设备表单用）
     */
    @GetMapping("/options")
    @PreAuthorize("hasRole('super_admin') or hasRole('tenant_admin') or hasRole('ops')")
    public R<List<EdgeGateway>> options() {
        return R.ok(edgeGatewayService.listOptions());
    }

    /**
     * 新增网关（自动生成编码与 Token）
     */
    @PostMapping
    @PreAuthorize("hasRole('super_admin') or hasRole('tenant_admin')")
    @Log(module="边缘网关", action="新增网关")
    public R<Map<String, String>> add(@RequestBody EdgeGateway gateway) {
        // 明文 Token 仅本次返回一次（库内为密文）
        String plainToken = edgeGatewayService.addGateway(gateway);
        return R.ok(Map.of("gatewayToken", plainToken));
    }

    /**
     * 修改网关
     */
    @PutMapping
    @PreAuthorize("hasRole('super_admin') or hasRole('tenant_admin')")
    @Log(module="边缘网关", action="修改网关")
    public R<Void> update(@RequestBody EdgeGateway gateway) {
        edgeGatewayService.updateGateway(gateway);
        return R.ok();
    }

    /**
     * 删除网关（逻辑删除；网关下存在设备时禁止删除）
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('super_admin') or hasRole('tenant_admin')")
    @Log(module="边缘网关", action="删除网关")
    public R<Void> delete(@PathVariable Long id) {
        edgeGatewayService.deleteGateway(id);
        return R.ok();
    }

    /**
     * 重置网关 Token（旧 Token 立即失效）
     */
    @PutMapping("/resetToken/{id}")
    @PreAuthorize("hasRole('super_admin') or hasRole('tenant_admin')")
    @Log(module="边缘网关", action="重置网关Token")
    public R<Map<String, String>> resetToken(@PathVariable Long id) {
        return R.ok(Map.of("gatewayToken", edgeGatewayService.resetToken(id)));
    }

    /**
     * 查看网关 PushPlus Token（脱敏显示；未配置返回 null）
     */
    @GetMapping("/{id}/pushplus-token")
    @PreAuthorize("hasRole('super_admin') or hasRole('tenant_admin')")
    public R<Map<String, String>> getPushplusToken(@PathVariable Long id) {
        return R.ok(java.util.Collections.singletonMap("pushplusToken", edgeGatewayService.getPushplusToken(id)));
    }

    /**
     * 设置/清空网关 PushPlus Token（body {"token":"..."}，空串清空；配置后该网关告警优先用网关级 Token）
     */
    @PutMapping("/{id}/pushplus-token")
    @PreAuthorize("hasRole('super_admin') or hasRole('tenant_admin')")
    @Log(module="边缘网关", action="配置PushPlus Token")
    public R<Void> setPushplusToken(@PathVariable Long id, @RequestBody Map<String, String> body) {
        edgeGatewayService.setPushplusToken(id, body.get("token"));
        return R.ok();
    }
}
