-- ============================================================
-- NetSight V1.2.2 侧边栏菜单图标修复
-- 根因：告警中心/运维工单/备品备件三个顶级菜单的 icon 值
-- (bell/tickets/box) 在本地 svg 图标库(src/assets/icons/svg)不存在，
-- svg-icon 渲染空白。
-- 修复：改为 Element UI 内置字体图标(el-icon-*)，前端 Item.vue 已支持
-- el-icon- 前缀渲染 <i class="el-icon-xxx">；以下 3 个图标均已确认
-- 存在于 element-ui 内置图标库(el-icon-bell/el-icon-tickets/el-icon-box)。
-- 执行：mysql --default-character-set=utf8mb4 -usadmin -p'***' netsight
-- ============================================================

UPDATE sys_permission SET icon = 'el-icon-bell'    WHERE perm_key = 'alert:menu'     AND del_flag = 0;
UPDATE sys_permission SET icon = 'el-icon-tickets' WHERE perm_key = 'workorder:menu' AND del_flag = 0;
UPDATE sys_permission SET icon = 'el-icon-box'     WHERE perm_key = 'spare:menu'     AND del_flag = 0;

-- 校验：应返回 3 行，icon 均为 el-icon-* 前缀
SELECT id, perm_name, perm_key, icon FROM sys_permission
WHERE perm_key IN ('alert:menu','workorder:menu','spare:menu') AND del_flag = 0;
