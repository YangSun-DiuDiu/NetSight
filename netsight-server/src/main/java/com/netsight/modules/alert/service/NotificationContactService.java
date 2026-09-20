package com.netsight.modules.alert.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.netsight.common.core.PageResult;
import com.netsight.common.core.ResultCode;
import com.netsight.common.exception.ServiceException;
import com.netsight.framework.security.SecurityUtils;
import com.netsight.modules.alert.entity.NotificationContact;
import com.netsight.modules.alert.mapper.NotificationContactMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 通知联系人服务
 * CRUD（多租户隔离）+ 给规则/手动发送提供人员下拉 + 按通道解析投递地址
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationContactService {

    private final NotificationContactMapper contactMapper;

    /** 通道类型常量 */
    public static final String CHANNEL_SMS = "sms";
    public static final String CHANNEL_WECHAT = "wechat";
    public static final String CHANNEL_PUSHPLUS = "pushplus";

    /**
     * 管理端分页（非超管仅本租户）
     */
    public PageResult<NotificationContact> pageContact(long pageNum, long pageSize,
                                                       String name, String mobile, Integer status) {
        Page<NotificationContact> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<NotificationContact> wrapper = new LambdaQueryWrapper<NotificationContact>()
                .like(StringUtils.hasText(name), NotificationContact::getName, name)
                .like(StringUtils.hasText(mobile), NotificationContact::getMobile, mobile)
                .eq(status != null, NotificationContact::getStatus, status);
        if (!SecurityUtils.isSuperAdmin()) {
            wrapper.eq(NotificationContact::getTenantId, SecurityUtils.getTenantId());
        }
        wrapper.orderByDesc(NotificationContact::getId);
        Page<NotificationContact> result = contactMapper.selectPage(page, wrapper);
        return PageResult.of(result.getTotal(), result.getRecords());
    }

    /**
     * 下拉选项：本租户启用联系人（供规则/手动发送人员多选）
     */
    public List<NotificationContact> options() {
        LambdaQueryWrapper<NotificationContact> wrapper = new LambdaQueryWrapper<NotificationContact>()
                .eq(NotificationContact::getStatus, 1);
        if (!SecurityUtils.isSuperAdmin()) {
            wrapper.eq(NotificationContact::getTenantId, SecurityUtils.getTenantId());
        }
        wrapper.orderByAsc(NotificationContact::getName);
        return contactMapper.selectList(wrapper);
    }

    /**
     * 新增
     */
    @Transactional(rollbackFor = Exception.class)
    public void addContact(NotificationContact contact) {
        contact.setId(null);
        contact.setTenantId(SecurityUtils.getTenantId());
        if (!StringUtils.hasText(contact.getName())) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "姓名不能为空");
        }
        contactMapper.insert(contact);
    }

    /**
     * 修改
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateContact(NotificationContact contact) {
        NotificationContact exist = contactMapper.selectById(contact.getId());
        if (exist == null) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "联系人不存在");
        }
        checkTenantPermission(exist.getTenantId());
        contact.setTenantId(null); // 不允许改租户
        contactMapper.updateById(contact);
    }

    /**
     * 删除
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteContact(Long id) {
        NotificationContact exist = contactMapper.selectById(id);
        if (exist == null) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "联系人不存在");
        }
        checkTenantPermission(exist.getTenantId());
        contactMapper.deleteById(id);
    }

    /**
     * 按 ID 列表查启用联系人（异步线程无租户上下文，显式带 tenantId 防跨租户）
     */
    public List<NotificationContact> resolveContacts(List<Long> contactIds, Long tenantId) {
        if (CollectionUtils.isEmpty(contactIds)) {
            return Collections.emptyList();
        }
        LambdaQueryWrapper<NotificationContact> wrapper = new LambdaQueryWrapper<NotificationContact>()
                .in(NotificationContact::getId, contactIds)
                .eq(NotificationContact::getTenantId, tenantId)
                .eq(NotificationContact::getStatus, 1);
        return contactMapper.selectList(wrapper);
    }

    /**
     * 按通道解析投递地址（核心：不同通道用不同字段）
     * <ul>
     *   <li>sms      → 联系人 mobile</li>
     *   <li>wechat   → 联系人 wechatOpenid（未绑定的跳过并记 warning）</li>
     *   <li>pushplus → 返回空列表（PushPlus 由租户 token 一对多群发，不依赖具体接收人）</li>
     * </ul>
     */
    public List<String> resolveReceiversByChannel(List<NotificationContact> contacts, String channelType) {
        if (CollectionUtils.isEmpty(contacts)) {
            return Collections.emptyList();
        }
        if (CHANNEL_PUSHPLUS.equals(channelType)) {
            return Collections.emptyList();
        }
        if (CHANNEL_SMS.equals(channelType)) {
            return contacts.stream()
                    .map(NotificationContact::getMobile)
                    .filter(StringUtils::hasText)
                    .distinct()
                    .collect(Collectors.toList());
        }
        if (CHANNEL_WECHAT.equals(channelType)) {
            List<String> openids = contacts.stream()
                    .map(NotificationContact::getWechatOpenid)
                    .filter(StringUtils::hasText)
                    .distinct()
                    .collect(Collectors.toList());
            int noOpenid = contacts.size() - openids.size();
            if (noOpenid > 0) {
                log.warn("[通知联系人] {} 个联系人未绑定微信openid，公众号通道将跳过", noOpenid);
            }
            return openids;
        }
        // 未知通道：兜底用 mobile
        return contacts.stream()
                .map(NotificationContact::getMobile)
                .filter(StringUtils::hasText)
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * 跨租户防探
     */
    private void checkTenantPermission(Long targetTenantId) {
        if (targetTenantId == null) {
            throw new ServiceException(ResultCode.FORBIDDEN.getCode(), "联系人数据异常");
        }
        if (!SecurityUtils.isSuperAdmin() && !targetTenantId.equals(SecurityUtils.getTenantId())) {
            throw new ServiceException(ResultCode.FORBIDDEN.getCode(), "无权访问其他租户联系人");
        }
    }
}
