package com.netsight.modules.todo.controller;

import com.netsight.common.core.PageResult;
import com.netsight.common.core.R;
import com.netsight.modules.todo.service.TodoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 统一待办控制器（demo 4/5）
 * 聚合工单待处理 / 派给我的维修工单 / 待执行点检任务
 * 全部角色可访问（todo:list 查询 / todo:view 去处理）
 */
@Slf4j
@RestController
@RequestMapping("/todo")
@RequiredArgsConstructor
public class TodoController {

    private final TodoService todoService;

    /**
     * 待办统计（Navbar 角标 + 页面统计卡）
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('todo:list')")
    @GetMapping("/stats")
    public R<Map<String, Object>> stats() {
        return R.ok(todoService.stats());
    }

    /**
     * 待办分页（type=all|work_order|inspection）
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('todo:list')")
    @GetMapping("/page")
    public R<PageResult<TodoService.TodoItem>> page(@RequestParam(defaultValue = "all") String type,
                                                    @RequestParam(defaultValue = "1") long pageNum,
                                                    @RequestParam(defaultValue = "10") long pageSize) {
        return R.ok(todoService.page(type, pageNum, pageSize));
    }
}
