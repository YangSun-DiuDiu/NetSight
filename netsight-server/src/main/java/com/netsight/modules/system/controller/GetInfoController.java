package com.netsight.modules.system.controller;

import com.netsight.common.core.R;
import com.netsight.framework.security.LoginUser;
import com.netsight.framework.security.SecurityUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * 用户信息接口
 * 登录成功后前端调用，获取当前用户信息、角色、权限
 */
@RestController
@Slf4j
public class GetInfoController {

    /**
     * 获取当前登录用户信息
     * GET /getInfo
     */
    @GetMapping("/getInfo")
    public R<Map<String, Object>> getInfo() {
        LoginUser loginUser = SecurityUtils.getLoginUser();
        Map<String, Object> user = new HashMap<>();
        user.put("userId", loginUser.getUserId());
        user.put("userName", loginUser.getUsername());
        user.put("nickName", loginUser.getRealName());
        user.put("phone", loginUser.getPhone());
        user.put("tenantId", loginUser.getTenantId());
        user.put("avatar", "");

        Map<String, Object> result = new HashMap<>();
        result.put("user", user);
        // 骨架阶段：角色权限后续由角色模块填充
        result.put("roles", loginUser.getRoles());
        result.put("permissions", loginUser.getPermissions());
        return R.ok(result);
    }
}
