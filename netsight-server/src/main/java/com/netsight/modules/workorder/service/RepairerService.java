package com.netsight.modules.workorder.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.netsight.common.core.PageResult;
import com.netsight.common.exception.ServiceException;
import com.netsight.framework.security.SecurityUtils;
import com.netsight.modules.system.dto.SysUserOption;
import com.netsight.modules.system.entity.SysUser;
import com.netsight.modules.system.mapper.SysUserMapper;
import com.netsight.modules.system.service.SysUserService;
import com.netsight.modules.workorder.constant.RepairerType;
import com.netsight.modules.workorder.dto.RepairerSaveResult;
import com.netsight.modules.workorder.entity.Repairer;
import com.netsight.modules.workorder.entity.WorkOrder;
import com.netsight.modules.workorder.mapper.RepairerMapper;
import com.netsight.modules.workorder.mapper.WorkOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 维修人员库服务：内聘绑定已有用户，外聘同事务建号建档案。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RepairerService {

    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Pattern PHONE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");

    private final RepairerMapper repairerMapper;
    private final WorkOrderMapper workOrderMapper;
    private final SysUserMapper sysUserMapper;
    private final SysUserService sysUserService;

    public PageResult<Repairer> pageRepairer(long pageNum, long pageSize, String keyword,
                                             String name, Integer status) {
        String search = StringUtils.hasText(keyword) ? keyword : name;
        Page<Repairer> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<Repairer> wrapper = new LambdaQueryWrapper<Repairer>()
                .and(StringUtils.hasText(search), q -> q
                        .like(Repairer::getName, search)
                        .or()
                        .like(Repairer::getPhone, search))
                .eq(status != null, Repairer::getStatus, status)
                .orderByDesc(Repairer::getId);
        Page<Repairer> result = repairerMapper.selectPage(page, wrapper);
        fillAccountInfo(result.getRecords());
        return PageResult.of(result.getTotal(), result.getRecords());
    }

    public PageResult<SysUserOption> pageUserOptions(long pageNum, long pageSize, String keyword) {
        long safePageNum = Math.max(pageNum, 1);
        long safePageSize = Math.min(Math.max(pageSize, 1), 500);
        Long tenantId = SecurityUtils.getTenantId();
        long total = sysUserMapper.countRepairerUserOptions(tenantId, keyword);
        List<SysUserOption> rows = sysUserMapper.selectRepairerUserOptions(
                tenantId, keyword, (safePageNum - 1) * safePageSize, safePageSize);
        return PageResult.of(total, rows);
    }

    public List<Repairer> listOptions(Long workOrderId) {
        if (workOrderId == null) {
            throw new ServiceException(500, "缺少工单编号，无法选择维修人员");
        }
        WorkOrder order = workOrderMapper.selectById(workOrderId);
        if (order == null) {
            throw new ServiceException(500, "工单不存在");
        }
        return repairerMapper.selectList(new LambdaQueryWrapper<Repairer>()
                .eq(Repairer::getTenantId, order.getTenantId())
                .eq(Repairer::getStatus, 1)
                .and(q -> q.ne(Repairer::getRepairerType, RepairerType.EXTERNAL)
                        .or().isNotNull(Repairer::getUserId))
                .orderByAsc(Repairer::getId));
    }

    @Transactional(rollbackFor = Exception.class)
    public RepairerSaveResult addRepairer(Repairer request) {
        if (request == null || request.getRepairerType() == null
                || request.getRepairerType() == RepairerType.UNCLASSIFIED) {
            throw new ServiceException(500, "请选择人员类型");
        }
        Long tenantId = SecurityUtils.getTenantId();
        if (request.getRepairerType() == RepairerType.INTERNAL) {
            return bindInternal(request, tenantId);
        }
        if (request.getRepairerType() == RepairerType.EXTERNAL) {
            return createExternal(request, tenantId);
        }
        throw new ServiceException(500, "人员类型不正确");
    }

    @Transactional(rollbackFor = Exception.class)
    public RepairerSaveResult bindAccount(Long id, Integer repairerType) {
        Repairer existing = checkTenant(id);
        if (existing == null) {
            throw new ServiceException(500, "维修人员不存在");
        }
        if (existing.getUserId() != null) {
            throw new ServiceException(500, "该维修人员已绑定账号");
        }
        if (!StringUtils.hasText(existing.getPhone()) || !PHONE_PATTERN.matcher(existing.getPhone()).matches()) {
            throw new ServiceException(500, "该维修人员未维护手机号，无法登录 H5");
        }
        if (countRepairerByPhone(existing.getTenantId(), existing.getPhone(), existing.getId()) > 0) {
            throw new ServiceException(500, "该手机号已存在其他维修人员档案");
        }
        if (repairerType == null || repairerType == RepairerType.UNCLASSIFIED) {
            throw new ServiceException(500, "请选择人员类型");
        }
        if (repairerType == RepairerType.INTERNAL) {
            List<SysUser> users = sysUserService.listUnboundUsersByPhone(
                    existing.getTenantId(), existing.getPhone());
            if (users.isEmpty()) {
                throw new ServiceException(500, "未找到同手机号用户，请改用外聘建号");
            }
            if (users.size() > 1) {
                throw new ServiceException(500, "手机号关联多个账号，请联系管理员");
            }
            SysUser user = users.get(0);
            if (!StringUtils.hasText(user.getRealName())) {
                throw new ServiceException(500, "该用户未维护姓名，无法绑定为维修人员");
            }
            if (sysUserService.hasRole(user.getId(), "super_admin")) {
                throw new ServiceException(500, "超级管理员不能绑定为维修人员");
            }
            existing.setUserId(user.getId());
            existing.setRepairerType(RepairerType.INTERNAL);
            existing.setName(user.getRealName());
            existing.setPhone(user.getPhone());
            repairerMapper.updateById(existing);
            sysUserService.ensureRole(user.getId(), "repairer");
            return new RepairerSaveResult(existing.getId(), user.getUsername(), false);
        }
        if (repairerType != RepairerType.EXTERNAL) {
            throw new ServiceException(500, "人员类型不正确");
        }
        if (!StringUtils.hasText(existing.getName())) {
            throw new ServiceException(500, "姓名不能为空");
        }
        if (!StringUtils.hasText(existing.getPhone())
                || !PHONE_PATTERN.matcher(existing.getPhone()).matches()) {
            throw new ServiceException(500, "手机号格式不正确");
        }
        List<SysUser> users = sysUserService.listUnboundUsersByPhone(
                existing.getTenantId(), existing.getPhone());
        if (users.size() > 1) {
            throw new ServiceException(500, "手机号关联多个账号，请联系管理员");
        }
        SysUser user;
        boolean accountCreated = false;
        if (users.size() == 1) {
            user = users.get(0);
            if (!StringUtils.hasText(user.getRealName())) {
                throw new ServiceException(500, "该用户未维护姓名，无法绑定为维修人员");
            }
            if (sysUserService.hasRole(user.getId(), "super_admin")) {
                throw new ServiceException(500, "超级管理员不能绑定为维修人员");
            }
            sysUserService.ensureRole(user.getId(), "repairer");
        } else {
            if (sysUserService.countUsersByPhoneInTenant(
                    existing.getTenantId(), existing.getPhone(), null) > 0) {
                throw new ServiceException(500, "该手机号已被本租户用户占用，请改用内聘选择");
            }
            user = sysUserService.createRepairerAccount(
                    existing.getTenantId(), existing.getName(), existing.getPhone());
            accountCreated = true;
        }
        existing.setUserId(user.getId());
        existing.setRepairerType(RepairerType.EXTERNAL);
        repairerMapper.updateById(existing);
        return new RepairerSaveResult(existing.getId(), user.getUsername(), accountCreated);
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateRepairer(Repairer request) {
        Repairer existing = checkTenant(request.getId());
        if (existing == null) {
            throw new ServiceException(500, "维修人员不存在");
        }
        Repairer update = new Repairer();
        update.setId(request.getId());
        update.setRegion(request.getRegion());
        update.setDeviceTypes(request.getDeviceTypes());
        update.setSkills(request.getSkills());
        if (request.getStatus() != null) {
            update.setStatus(request.getStatus());
        }
        update.setRemark(request.getRemark());
        if (existing.getRepairerType() != null
                && existing.getRepairerType() == RepairerType.EXTERNAL) {
            String name = StringUtils.hasText(request.getName()) ? request.getName() : existing.getName();
            String phone = StringUtils.hasText(request.getPhone()) ? request.getPhone() : existing.getPhone();
            if (!StringUtils.hasText(name)) {
                throw new ServiceException(500, "姓名不能为空");
            }
            if (!StringUtils.hasText(phone) || !PHONE_PATTERN.matcher(phone).matches()) {
                throw new ServiceException(500, "手机号格式不正确");
            }
            if (!Objects.equals(phone, existing.getPhone())) {
                if (sysUserService.countUsersByPhoneInTenant(
                        existing.getTenantId(), phone, existing.getUserId()) > 0) {
                    throw new ServiceException(500, "该手机号已被本租户用户占用，请改用内聘选择");
                }
                if (countRepairerByPhone(existing.getTenantId(), phone, existing.getId()) > 0) {
                    throw new ServiceException(500, "该手机号已存在其他维修人员档案");
                }
            }
            if (existing.getUserId() != null
                    && (!Objects.equals(name, existing.getName())
                        || !Objects.equals(phone, existing.getPhone()))) {
                SysUser userUpdate = new SysUser();
                userUpdate.setId(existing.getUserId());
                userUpdate.setRealName(name);
                userUpdate.setPhone(phone);
                sysUserMapper.updateById(userUpdate);
            }
            update.setName(name);
            update.setPhone(phone);
        }
        repairerMapper.updateById(update);
    }

    @Transactional(rollbackFor = Exception.class)
    public void removeRepairer(Long id) {
        Repairer existing = checkTenant(id);
        if (existing == null) {
            throw new ServiceException(500, "维修人员不存在");
        }
        if (existing.getRepairerType() != null
                && existing.getRepairerType() == RepairerType.EXTERNAL
                && existing.getUserId() != null) {
            sysUserService.disableRepairerAccountIfOnlyRole(existing.getUserId());
        }
        repairerMapper.deleteById(id);
    }

    private RepairerSaveResult bindInternal(Repairer request, Long tenantId) {
        if (request.getUserId() == null) {
            throw new ServiceException(500, "请选择用户");
        }
        SysUser user = sysUserMapper.selectByIdIgnoreTenant(request.getUserId());
        if (user == null) {
            throw new ServiceException(500, "用户不存在");
        }
        if (!Objects.equals(user.getTenantId(), tenantId)) {
            throw new ServiceException(500, "该用户不属于当前租户");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new ServiceException(500, "该用户已被禁用，请先在用户管理中启用");
        }
        if (!StringUtils.hasText(user.getRealName())) {
            throw new ServiceException(500, "该用户未维护姓名，无法绑定为维修人员");
        }
        if (!StringUtils.hasText(user.getPhone()) || !PHONE_PATTERN.matcher(user.getPhone()).matches()) {
            throw new ServiceException(500, "该用户未维护手机号，无法登录 H5");
        }
        if (sysUserService.hasRole(user.getId(), "super_admin")) {
            throw new ServiceException(500, "超级管理员不能绑定为维修人员");
        }
        if (countBound(tenantId, user.getId()) > 0) {
            throw new ServiceException(500, "该用户已绑定其他维修人员档案");
        }
        if (countRepairerByPhone(tenantId, user.getPhone(), null) > 0) {
            throw new ServiceException(500, "该手机号已存在其他维修人员档案");
        }
        Repairer repairer = new Repairer();
        copyEditableFields(request, repairer);
        repairer.setTenantId(tenantId);
        repairer.setRepairerNo(genNo("REP"));
        repairer.setUserId(user.getId());
        repairer.setRepairerType(RepairerType.INTERNAL);
        repairer.setName(user.getRealName());
        repairer.setPhone(user.getPhone());
        if (repairer.getStatus() == null) {
            repairer.setStatus(1);
        }
        repairerMapper.insert(repairer);
        sysUserService.ensureRole(user.getId(), "repairer");
        return new RepairerSaveResult(repairer.getId(), user.getUsername(), false);
    }

    private RepairerSaveResult createExternal(Repairer request, Long tenantId) {
        if (!StringUtils.hasText(request.getName())) {
            throw new ServiceException(500, "姓名不能为空");
        }
        if (!StringUtils.hasText(request.getPhone())
                || !PHONE_PATTERN.matcher(request.getPhone()).matches()) {
            throw new ServiceException(500, "手机号格式不正确");
        }
        if (sysUserService.countUsersByPhoneInTenant(tenantId, request.getPhone(), null) > 0) {
            throw new ServiceException(500, "该手机号已被本租户用户占用，请改用内聘选择");
        }
        if (countRepairerByPhone(tenantId, request.getPhone(), null) > 0) {
            throw new ServiceException(500, "该手机号已存在其他维修人员档案");
        }
        SysUser user = sysUserService.createRepairerAccount(
                tenantId, request.getName(), request.getPhone());
        Repairer repairer = new Repairer();
        copyEditableFields(request, repairer);
        repairer.setTenantId(tenantId);
        repairer.setRepairerNo(genNo("REP"));
        repairer.setUserId(user.getId());
        repairer.setRepairerType(RepairerType.EXTERNAL);
        repairer.setName(request.getName());
        repairer.setPhone(request.getPhone());
        if (repairer.getStatus() == null) {
            repairer.setStatus(1);
        }
        repairerMapper.insert(repairer);
        return new RepairerSaveResult(repairer.getId(), user.getUsername(), true);
    }

    private void copyEditableFields(Repairer source, Repairer target) {
        target.setRegion(source.getRegion());
        target.setDeviceTypes(source.getDeviceTypes());
        target.setSkills(source.getSkills());
        target.setStatus(source.getStatus());
        target.setRemark(source.getRemark());
    }

    private void fillAccountInfo(List<Repairer> repairers) {
        Set<Long> userIds = repairers.stream()
                .map(Repairer::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (userIds.isEmpty()) {
            return;
        }
        Map<Long, SysUser> userMap = sysUserMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(SysUser::getId, user -> user));
        for (Repairer repairer : repairers) {
            SysUser user = userMap.get(repairer.getUserId());
            if (user != null) {
                repairer.setLoginUsername(user.getUsername());
                repairer.setUserStatus(user.getStatus());
            }
        }
    }

    private long countBound(Long tenantId, Long userId) {
        return repairerMapper.selectCount(new LambdaQueryWrapper<Repairer>()
                .eq(Repairer::getTenantId, tenantId)
                .eq(Repairer::getUserId, userId));
    }

    private long countRepairerByPhone(Long tenantId, String phone, Long excludeId) {
        LambdaQueryWrapper<Repairer> wrapper = new LambdaQueryWrapper<Repairer>()
                .eq(Repairer::getTenantId, tenantId)
                .eq(Repairer::getPhone, phone);
        if (excludeId != null) {
            wrapper.ne(Repairer::getId, excludeId);
        }
        return repairerMapper.selectCount(wrapper);
    }

    private Repairer checkTenant(Long id) {
        return repairerMapper.selectOne(new LambdaQueryWrapper<Repairer>()
                .eq(Repairer::getId, id)
                .eq(Repairer::getTenantId, SecurityUtils.getTenantId()));
    }

    private String genNo(String prefix) {
        return prefix + LocalDateTime.now().format(NO_FMT)
                + ThreadLocalRandom.current().nextInt(1000, 10000);
    }
}
