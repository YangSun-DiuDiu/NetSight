package com.netsight.modules.workorder.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.netsight.common.core.PageResult;
import com.netsight.common.exception.ServiceException;
import com.netsight.framework.security.SecurityUtils;
import com.netsight.modules.workorder.entity.Repairer;
import com.netsight.modules.workorder.mapper.RepairerMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 维修人员库服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RepairerService {

    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final RepairerMapper repairerMapper;

    /**
     * 分页查询（租户隔离自动处理）
     */
    public PageResult<Repairer> pageRepairer(long pageNum, long pageSize, String name, Integer status) {
        Page<Repairer> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<Repairer> wrapper = new LambdaQueryWrapper<Repairer>()
                .like(StringUtils.hasText(name), Repairer::getName, name)
                .eq(status != null, Repairer::getStatus, status)
                .orderByDesc(Repairer::getId);
        Page<Repairer> result = repairerMapper.selectPage(page, wrapper);
        return PageResult.of(result.getTotal(), result.getRecords());
    }

    /**
     * 在岗维修人员选项（派单弹窗下拉）
     */
    public List<Repairer> listOptions() {
        return repairerMapper.selectList(new LambdaQueryWrapper<Repairer>()
                .eq(Repairer::getStatus, 1).orderByAsc(Repairer::getId));
    }

    @Transactional(rollbackFor = Exception.class)
    public Long addRepairer(Repairer repairer) {
        repairer.setTenantId(SecurityUtils.getTenantId());
        repairer.setRepairerNo(genNo("REP"));
        if (repairer.getStatus() == null) {
            repairer.setStatus(1);
        }
        repairerMapper.insert(repairer);
        return repairer.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateRepairer(Repairer repairer) {
        Repairer exist = checkTenant(repairer.getId());
        if (exist == null) {
            throw new ServiceException(500, "维修人员不存在");
        }
        repairer.setRepairerNo(null);
        repairer.setTenantId(null);
        repairerMapper.updateById(repairer);
    }

    @Transactional(rollbackFor = Exception.class)
    public void removeRepairer(Long id) {
        Repairer exist = checkTenant(id);
        if (exist == null) {
            throw new ServiceException(500, "维修人员不存在");
        }
        repairerMapper.deleteById(id);
    }

    private Repairer checkTenant(Long id) {
        return repairerMapper.selectOne(new LambdaQueryWrapper<Repairer>()
                .eq(Repairer::getId, id).eq(Repairer::getTenantId, SecurityUtils.getTenantId()));
    }

    private String genNo(String prefix) {
        return prefix + LocalDateTime.now().format(NO_FMT) + ThreadLocalRandom.current().nextInt(1000, 10000);
    }
}
