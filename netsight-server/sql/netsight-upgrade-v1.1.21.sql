-- =====================================================================
-- NetSight（NAMS）V1.1.21 增量升级脚本（点检巡检全面修复）
-- 1. inspection_plan 增加 item_ids（计划绑定点检项）、cycle_day（周期自定义）
-- 2. inspection_task  增加 item_ids（任务生成时快照点检项）
-- 适用：192.168.1.55 MySQL（库 netsight）
-- =====================================================================

-- 1. 点检计划：绑定"本计划应检查哪些点检项"（JSON 数组，如 [1,2,3]；空=执行时加载全部启用项，兼容旧数据）
ALTER TABLE inspection_plan
    ADD COLUMN item_ids VARCHAR(1024) DEFAULT NULL COMMENT '点检项ID列表（JSON数组），空=全部启用项' AFTER target_ids;

-- 2. 点检计划：周期自定义日期（weekly=周几 1-7，默认1=周一；monthly=每月几号 1-31，默认1）
ALTER TABLE inspection_plan
    ADD COLUMN cycle_day VARCHAR(16) DEFAULT NULL COMMENT '周期自定义：weekly=周几1-7 / monthly=每月几号1-31' AFTER cycle_type;

-- 3. 点检任务：生成任务时快照计划绑定的点检项（JSON 数组；空=执行时回退全部启用项，兼容旧任务）
ALTER TABLE inspection_task
    ADD COLUMN item_ids VARCHAR(1024) DEFAULT NULL COMMENT '点检项ID列表快照（JSON数组）' AFTER plan_name;

-- 校验
SELECT id, plan_name, cycle_type, cycle_day, item_ids FROM inspection_plan WHERE del_flag = 0;
SELECT id, task_no, item_ids FROM inspection_task WHERE del_flag = 0 LIMIT 5;
