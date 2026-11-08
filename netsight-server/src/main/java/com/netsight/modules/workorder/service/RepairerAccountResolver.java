package com.netsight.modules.workorder.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.netsight.common.exception.ServiceException;
import com.netsight.framework.security.LoginUser;
import com.netsight.modules.workorder.constant.RepairerType;
import com.netsight.modules.workorder.entity.Repairer;
import com.netsight.modules.workorder.mapper.RepairerMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 维修档案解析器：统一 H5 登录与移动端当前维修员的身份匹配规则。
 */
@Component
@RequiredArgsConstructor
public class RepairerAccountResolver {

    private final RepairerMapper repairerMapper;

    public List<Repairer> listBound(Long tenantId, Long userId) {
        if (tenantId == null || userId == null) {
            return List.of();
        }
        return repairerMapper.selectList(new LambdaQueryWrapper<Repairer>()
                .eq(Repairer::getTenantId, tenantId)
                .eq(Repairer::getUserId, userId)
                .orderByAsc(Repairer::getId));
    }

    public List<Repairer> listLegacyByPhone(Long tenantId, String phone) {
        if (tenantId == null || !StringUtils.hasText(phone)) {
            return List.of();
        }
        return repairerMapper.selectList(new LambdaQueryWrapper<Repairer>()
                .eq(Repairer::getTenantId, tenantId)
                .eq(Repairer::getPhone, phone)
                .isNull(Repairer::getUserId)
                .ne(Repairer::getRepairerType, RepairerType.EXTERNAL)
                .orderByAsc(Repairer::getId));
    }

    public Repairer findByIdAndTenant(Long id, Long tenantId) {
        if (id == null || tenantId == null) {
            return null;
        }
        return repairerMapper.selectOne(new LambdaQueryWrapper<Repairer>()
                .eq(Repairer::getId, id)
                .eq(Repairer::getTenantId, tenantId));
    }

    /**
     * 按 JWT repairerId → userId → 存量手机号 的顺序解析当前维修档案。
     */
    public Repairer resolve(LoginUser loginUser) {
        if (loginUser == null || loginUser.getTenantId() == null) {
            throw new ServiceException(500, "当前账号未关联维修人员档案，请联系管理员");
        }
        Repairer repairer = findByIdAndTenant(loginUser.getRepairerId(), loginUser.getTenantId());
        if (repairer == null) {
            List<Repairer> bound = listBound(loginUser.getTenantId(), loginUser.getUserId());
            if (bound.size() > 1) {
                throw new ServiceException(500, "手机号关联多个维修人员账号，请联系管理员处理");
            }
            if (bound.size() == 1) {
                repairer = bound.get(0);
            }
        }
        if (repairer == null) {
            List<Repairer> legacy = listLegacyByPhone(loginUser.getTenantId(), loginUser.getPhone());
            if (legacy.size() > 1) {
                throw new ServiceException(500, "手机号关联多个维修人员账号，请联系管理员处理");
            }
            if (legacy.size() == 1) {
                repairer = legacy.get(0);
            }
        }
        if (repairer == null) {
            throw new ServiceException(500, "当前账号未关联维修人员档案，请联系管理员");
        }
        assertUsable(repairer);
        return repairer;
    }

    public void assertUsable(Repairer repairer) {
        if (repairer.getRepairerType() != null
                && repairer.getRepairerType() == RepairerType.EXTERNAL
                && repairer.getUserId() == null) {
            throw new ServiceException(500,
                    "该外聘维修人员未绑定登录账号，无法 H5 接单，请先补建账号");
        }
        if (repairer.getStatus() == null || repairer.getStatus() == 1) {
            return;
        }
        if (repairer.getStatus() == 0) {
            throw new ServiceException(500,
                    "当前维修人员为休假状态，暂不能登录 H5，请联系管理员调整在岗状态");
        }
        if (repairer.getStatus() == 2) {
            throw new ServiceException(500, "当前维修人员已离职，不能登录 H5");
        }
        throw new ServiceException(500, "当前维修人员状态异常，暂不能登录 H5，请联系管理员");
    }
}
