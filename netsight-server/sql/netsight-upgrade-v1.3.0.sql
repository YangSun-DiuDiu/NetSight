-- NetSight V1.3 升级：工单表加维修照片字段 + 移动端工单功能
-- 执行时间：2026-09-23

USE netsight;

-- 1. work_order 表加维修照片字段
ALTER TABLE work_order ADD COLUMN repair_photos VARCHAR(2000) NULL COMMENT '维修照片URL JSON数组' AFTER repair_result;

-- 2. 创建上传目录（服务器执行）
-- mkdir -p /opt/nams-server/uploads
