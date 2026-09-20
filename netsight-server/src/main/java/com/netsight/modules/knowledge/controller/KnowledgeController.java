package com.netsight.modules.knowledge.controller;

import com.netsight.common.core.PageResult;
import com.netsight.common.core.R;
import com.netsight.framework.aspectj.Log;
import com.netsight.modules.knowledge.entity.FaultArticle;
import com.netsight.modules.knowledge.service.KnowledgeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 故障知识库控制器
 * 管理端：知识条目 CRUD + 统计（knowledge:fault:* 权限，role1/2）
 * 查阅端：列表 + 详情 + 工单联动推荐（role3/4 可查可看详情，无管理）
 */
@Slf4j
@RestController
@RequestMapping("/knowledge/fault")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    /**
     * 分页查询
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('knowledge:fault:list')")
    @GetMapping("/list")
    public R<PageResult<FaultArticle>> list(@RequestParam(defaultValue = "1") long pageNum,
                                            @RequestParam(defaultValue = "10") long pageSize,
                                            @RequestParam(required = false) String title,
                                            @RequestParam(required = false) String category,
                                            @RequestParam(required = false) String deviceType,
                                            @RequestParam(required = false) Integer status) {
        return R.ok(knowledgeService.pageFault(pageNum, pageSize, title, category, deviceType, status));
    }

    /**
     * 详情（浏览计数 +1）
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('knowledge:fault:view')")
    @GetMapping("/{id}")
    public R<FaultArticle> detail(@PathVariable Long id) {
        return R.ok(knowledgeService.getDetail(id));
    }

    /**
     * 统计
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('knowledge:fault:list')")
    @GetMapping("/stats")
    public R<Map<String, Object>> stats() {
        return R.ok(knowledgeService.stats());
    }

    /**
     * 工单联动推荐（设备类型 + 故障类型匹配，最多 5 条）
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('knowledge:fault:view')")
    @GetMapping("/recommend")
    public R<List<FaultArticle>> recommend(@RequestParam(required = false) String deviceType,
                                           @RequestParam(required = false) String faultType) {
        return R.ok(knowledgeService.recommend(deviceType, faultType));
    }

    /**
     * 下拉选项
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('knowledge:fault:list')")
    @GetMapping("/options")
    public R<Map<String, List<String>>> options() {
        return R.ok(knowledgeService.options());
    }

    /**
     * 新增
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('knowledge:fault:add')")
    @PostMapping
    @Log(module = "知识库", action = "新增故障知识")
    public R<Void> add(@RequestBody FaultArticle article) {
        knowledgeService.addFault(article);
        return R.ok();
    }

    /**
     * 修改
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('knowledge:fault:edit')")
    @PutMapping
    @Log(module = "知识库", action = "修改故障知识")
    public R<Void> update(@RequestBody FaultArticle article) {
        knowledgeService.updateFault(article);
        return R.ok();
    }

    /**
     * 删除
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('knowledge:fault:remove')")
    @DeleteMapping("/{id}")
    @Log(module = "知识库", action = "删除故障知识")
    public R<Void> delete(@PathVariable Long id) {
        knowledgeService.deleteFault(id);
        return R.ok();
    }
}
