package com.netsight.modules.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.netsight.common.core.PageResult;
import com.netsight.common.core.ResultCode;
import com.netsight.common.exception.ServiceException;
import com.netsight.framework.security.SecurityUtils;
import com.netsight.modules.knowledge.entity.FaultArticle;
import com.netsight.modules.knowledge.mapper.FaultArticleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 故障知识库服务
 * 知识条目 CRUD（多租户隔离）+ 详情浏览计数 + 分类统计 + 工单联动推荐
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeService {

    private final FaultArticleMapper faultArticleMapper;

    /** 内置分类 */
    private static final List<String> CATEGORIES = Arrays.asList(
            "设备离线", "链路异常", "视频故障", "门禁故障", "性能问题", "其他");

    /** 内置设备类型 */
    private static final List<String> DEVICE_TYPES = Arrays.asList(
            "all", "network", "camera", "nvr", "door_controller");

    /** 参考级别 */
    private static final List<String> SEVERITIES = Arrays.asList(
            "critical", "warning", "info");

    /**
     * 管理端分页查询（非超管仅本租户）
     */
    public PageResult<FaultArticle> pageFault(long pageNum, long pageSize, String title,
                                              String category, String deviceType, Integer status) {
        Page<FaultArticle> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<FaultArticle> wrapper = new LambdaQueryWrapper<FaultArticle>()
                .like(StringUtils.hasText(title), FaultArticle::getTitle, title)
                .like(StringUtils.hasText(category), FaultArticle::getCategory, category)
                .eq(StringUtils.hasText(deviceType), FaultArticle::getDeviceType, deviceType)
                .eq(status != null, FaultArticle::getStatus, status);
        if (!SecurityUtils.isSuperAdmin()) {
            wrapper.eq(FaultArticle::getTenantId, SecurityUtils.getTenantId());
        }
        wrapper.orderByDesc(FaultArticle::getId);
        Page<FaultArticle> result = faultArticleMapper.selectPage(page, wrapper);
        return PageResult.of(result.getTotal(), result.getRecords());
    }

    /**
     * 详情（浏览计数 +1）
     */
    @Transactional(rollbackFor = Exception.class)
    public FaultArticle getDetail(Long id) {
        FaultArticle article = faultArticleMapper.selectById(id);
        if (article == null) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "故障知识不存在");
        }
        checkTenantPermission(article.getTenantId());
        // 浏览计数原子 +1（并发安全）
        faultArticleMapper.update(null, new LambdaUpdateWrapper<FaultArticle>()
                .eq(FaultArticle::getId, id)
                .setSql("view_count = view_count + 1"));
        article.setViewCount(article.getViewCount() == null ? 1 : article.getViewCount() + 1);
        return article;
    }

    /**
     * 新增
     */
    @Transactional(rollbackFor = Exception.class)
    public void addFault(FaultArticle article) {
        article.setId(null);
        article.setTenantId(SecurityUtils.getTenantId());
        article.setCreateBy(currentUsername());
        article.setViewCount(article.getViewCount() == null ? 0 : article.getViewCount());
        if (article.getStatus() == null) {
            article.setStatus(1);
        }
        faultArticleMapper.insert(article);
    }

    /**
     * 修改
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateFault(FaultArticle article) {
        FaultArticle exist = faultArticleMapper.selectById(article.getId());
        if (exist == null) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "故障知识不存在");
        }
        checkTenantPermission(exist.getTenantId());
        // 禁止篡改租户归属
        article.setTenantId(exist.getTenantId());
        article.setCreateBy(exist.getCreateBy());
        faultArticleMapper.updateById(article);
    }

    /**
     * 删除
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteFault(Long id) {
        FaultArticle exist = faultArticleMapper.selectById(id);
        if (exist == null) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "故障知识不存在");
        }
        checkTenantPermission(exist.getTenantId());
        faultArticleMapper.deleteById(id);
    }

    /**
     * 统计（总数/启用/停用/浏览总量 + 分类分布 + 设备类型分布）
     */
    public Map<String, Object> stats() {
        List<FaultArticle> all = listTenantAll();
        Map<String, Object> result = new HashMap<>();
        result.put("total", all.size());
        result.put("enabled", all.stream().filter(a -> a.getStatus() != null && a.getStatus() == 1).count());
        result.put("disabled", all.stream().filter(a -> a.getStatus() == null || a.getStatus() == 0).count());
        result.put("viewCount", all.stream()
                .mapToLong(a -> a.getViewCount() == null ? 0L : a.getViewCount()).sum());
        // 分类分布
        Map<String, Long> byCategory = all.stream()
                .collect(Collectors.groupingBy(a -> a.getCategory() == null ? "其他" : a.getCategory(),
                        Collectors.counting()));
        result.put("byCategory", byCategory);
        // 设备类型分布
        Map<String, Long> byDevice = all.stream()
                .collect(Collectors.groupingBy(a -> a.getDeviceType() == null ? "all" : a.getDeviceType(),
                        Collectors.counting()));
        result.put("byDevice", byDevice);
        return result;
    }

    /**
     * 工单联动推荐：按设备类型 + 故障类型匹配知识条目（浏览量优先，最多 5 条）
     * faultType 映射：offline→离线 / line_abnormal→链路,外线 / 其他→不限
     */
    public List<FaultArticle> recommend(String deviceType, String faultType) {
        LambdaQueryWrapper<FaultArticle> wrapper = new LambdaQueryWrapper<FaultArticle>()
                .eq(FaultArticle::getStatus, 1);
        if (!SecurityUtils.isSuperAdmin()) {
            wrapper.eq(FaultArticle::getTenantId, SecurityUtils.getTenantId());
        }
        // 设备类型：all 通用 + 精确匹配
        if (StringUtils.hasText(deviceType)) {
            wrapper.and(w -> w.eq(FaultArticle::getDeviceType, "all")
                    .or().eq(FaultArticle::getDeviceType, deviceType));
        }
        // 故障类型关键词匹配（分类或关键词字段）
        final String kw;
        if ("offline".equals(faultType)) {
            kw = "离线";
        } else if ("line_abnormal".equals(faultType)) {
            kw = "链路";
        } else {
            kw = null;
        }
        if (kw != null) {
            wrapper.and(w -> w.like(FaultArticle::getCategory, kw)
                    .or().like(FaultArticle::getKeywords, kw));
        }
        wrapper.orderByDesc(FaultArticle::getViewCount).orderByDesc(FaultArticle::getId)
                .last("LIMIT 5");
        return faultArticleMapper.selectList(wrapper);
    }

    /**
     * 下拉选项（分类/设备类型/参考级别）
     */
    public Map<String, List<String>> options() {
        Map<String, List<String>> result = new HashMap<>();
        result.put("categories", CATEGORIES);
        result.put("deviceTypes", DEVICE_TYPES);
        result.put("severities", SEVERITIES);
        return result;
    }

    /**
     * 跨租户防探（tenantId 为 null 属脏数据，一并拦截防 NPE/越权放行）
     */
    private void checkTenantPermission(Long tenantId) {
        if (tenantId == null) {
            throw new ServiceException(ResultCode.FORBIDDEN.getCode(), "无权访问其他租户的数据");
        }
        if (!SecurityUtils.isSuperAdmin() && !tenantId.equals(SecurityUtils.getTenantId())) {
            throw new ServiceException(ResultCode.FORBIDDEN.getCode(), "无权访问其他租户的数据");
        }
    }

    /**
     * 查询本租户全量（统计用）
     */
    private List<FaultArticle> listTenantAll() {
        LambdaQueryWrapper<FaultArticle> wrapper = new LambdaQueryWrapper<>();
        if (!SecurityUtils.isSuperAdmin()) {
            wrapper.eq(FaultArticle::getTenantId, SecurityUtils.getTenantId());
        }
        return faultArticleMapper.selectList(wrapper);
    }

    /**
     * 当前操作人（未登录兜底"系统"）
     */
    private String currentUsername() {
        try {
            return SecurityUtils.getLoginUser().getUsername();
        } catch (Exception e) {
            return "系统";
        }
    }
}
