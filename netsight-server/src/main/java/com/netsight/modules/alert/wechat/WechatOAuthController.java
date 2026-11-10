package com.netsight.modules.alert.wechat;

import com.netsight.common.core.R;
import com.netsight.framework.aspectj.Log;
import com.netsight.modules.alert.entity.NotificationContact;
import com.netsight.modules.alert.service.NotificationContactService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 微信公众号 OAuth 与联系人绑定端点。
 * /wechat/oauth/** 免登录；/alert/contact/** 绑定操作需登录态与联系人权限。
 */
@RestController
@RequiredArgsConstructor
public class WechatOAuthController {

    private final WechatOAuthService oauthService;
    private final NotificationContactService contactService;

    /** 登录前静默授权入口：仅允许 login 模式，bind 模式必须走管理端 bind-url。 */
    @GetMapping("/wechat/oauth/start")
    public ResponseEntity<Void> start(@RequestParam(defaultValue = "/m/login") String redirect,
                                      @RequestParam(required = false) Long channelId) {
        String url = oauthService.start(redirect, "login", null, channelId, null).getUrl();
        return redirect(url);
    }

    @GetMapping("/wechat/oauth/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state) {
        return redirect(oauthService.callback(code, state));
    }

    /** 管理端生成联系人绑定二维码。 */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('alert:contact:edit')")
    @GetMapping("/alert/contact/{id}/wechat/bind-url")
    public R<Map<String, String>> bindUrl(@PathVariable Long id,
                                          @RequestParam(defaultValue = "7") Long channelId) {
        NotificationContact contact = contactService.getByIdChecked(id);
        WechatOAuthService.StartResult result = oauthService.start(
                "/alert/contact", "bind", id, channelId, contact.getTenantId());
        return R.ok(Map.of("url", result.getUrl(), "ticket", result.getTicket() == null ? "" : result.getTicket()));
    }

    /** PC 轮询扫码绑定结果。 */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('alert:contact:list')")
    @GetMapping("/alert/contact/wechat/bind-status")
    public R<Map<String, Object>> bindStatus(@RequestParam String ticket) {
        return R.ok(oauthService.bindStatus(ticket));
    }

    /** 解绑微信。 */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('alert:contact:edit')")
    @PostMapping("/alert/contact/{id}/wechat/unbind")
    @Log(module = "通知联系人", action = "解绑微信")
    public R<Void> unbind(@PathVariable Long id) {
        contactService.unbindWechat(id);
        return R.ok();
    }

    private ResponseEntity<Void> redirect(String location) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, location)
                .build();
    }
}
