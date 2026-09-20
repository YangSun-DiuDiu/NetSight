-- =====================================================
-- NetSight 网络资产监控管理系统 建库脚本（骨架阶段）
-- 数据库: netsight
-- 字符集: utf8mb4
-- =====================================================
CREATE DATABASE IF NOT EXISTS `netsight` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE `netsight`;

-- -----------------------------------------------------
-- 1. 租户表（系统公共表，不参与租户过滤）
-- -----------------------------------------------------
DROP TABLE IF EXISTS `sys_tenant`;
CREATE TABLE `sys_tenant` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '租户ID',
    `tenant_name`    VARCHAR(128) NOT NULL COMMENT '租户名称（公司/部门名）',
    `contact_person` VARCHAR(64)  DEFAULT NULL COMMENT '联系人',
    `contact_phone`  VARCHAR(32)  DEFAULT NULL COMMENT '联系电话',
    `status`         TINYINT      NOT NULL DEFAULT 1 COMMENT '启用状态：1启用/0禁用',
    `expire_time`    DATETIME     DEFAULT NULL COMMENT '租户到期时间',
    `create_time`    DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `del_flag`       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常/2已删除',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户表';

-- -----------------------------------------------------
-- 2. 用户表（带 tenant_id，参与租户过滤）
-- -----------------------------------------------------
DROP TABLE IF EXISTS `sys_user`;
CREATE TABLE `sys_user` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    `tenant_id`      BIGINT       NOT NULL DEFAULT 0 COMMENT '所属租户（数据隔离关键字段）',
    `username`       VARCHAR(64)  NOT NULL COMMENT '登录账号',
    `password`       VARCHAR(128) DEFAULT NULL COMMENT '密码（BCrypt加密）',
    `real_name`      VARCHAR(64)  DEFAULT NULL COMMENT '真实姓名',
    `phone`          VARCHAR(32)  DEFAULT NULL COMMENT '手机号（PC端登录用）',
    `wechat_openid`  VARCHAR(128) DEFAULT NULL COMMENT '微信OpenID（小程序登录用）',
    `status`         TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1正常/0禁用',
    `create_time`    DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `del_flag`       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常/2已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    KEY `idx_phone` (`phone`),
    KEY `idx_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- -----------------------------------------------------
-- 3. 角色表（系统公共表）
-- -----------------------------------------------------
DROP TABLE IF EXISTS `sys_role`;
CREATE TABLE `sys_role` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '角色ID',
    `role_name`   VARCHAR(64)  NOT NULL COMMENT '角色名称',
    `role_key`    VARCHAR(64)  NOT NULL COMMENT '角色编码：super_admin/tenant_admin/ops/repairer',
    `role_sort`   INT          DEFAULT 0 COMMENT '显示顺序',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1正常/0停用',
    `remark`      VARCHAR(256) DEFAULT NULL COMMENT '备注',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `del_flag`    TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常/2已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_key` (`role_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色表';

-- -----------------------------------------------------
-- 4. 用户-角色关联表
-- -----------------------------------------------------
DROP TABLE IF EXISTS `sys_user_role`;
CREATE TABLE `sys_user_role` (
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `role_id` BIGINT NOT NULL COMMENT '角色ID',
    PRIMARY KEY (`user_id`, `role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户-角色关联表';

-- -----------------------------------------------------
-- 5. 权限表（菜单/按钮/接口权限）
-- -----------------------------------------------------
DROP TABLE IF EXISTS `sys_permission`;
CREATE TABLE `sys_permission` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '权限ID',
    `parent_id`   BIGINT       NOT NULL DEFAULT 0 COMMENT '父权限ID',
    `perm_name`   VARCHAR(64)  NOT NULL COMMENT '权限名称',
    `perm_key`    VARCHAR(128) NOT NULL COMMENT '权限标识：system:user:list',
    `perm_type`   VARCHAR(16)  NOT NULL DEFAULT 'button' COMMENT '类型：menu/button/api',
    `sort`        INT          DEFAULT 0 COMMENT '排序',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `del_flag`    TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常/2已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_perm_key` (`perm_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='权限表';

-- -----------------------------------------------------
-- 6. 角色-权限关联表
-- -----------------------------------------------------
DROP TABLE IF EXISTS `sys_role_permission`;
CREATE TABLE `sys_role_permission` (
    `role_id`       BIGINT NOT NULL COMMENT '角色ID',
    `permission_id` BIGINT NOT NULL COMMENT '权限ID',
    PRIMARY KEY (`role_id`, `permission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色-权限关联表';

-- =====================================================
-- 初始化数据
-- =====================================================
-- 默认租户
INSERT INTO `sys_tenant` (`id`, `tenant_name`, `contact_person`, `contact_phone`, `status`) VALUES
(1, 'NetSight 默认租户', '管理员', '13800000000', 1);

-- 默认用户：admin / admin123（BCrypt 加密），开发规范约定业务账号统一 admin123
-- BCrypt("admin123") = $2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2
INSERT INTO `sys_user` (`id`, `tenant_id`, `username`, `password`, `real_name`, `phone`, `status`) VALUES
(1, 1, 'admin', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2', '系统管理员', '13800000000', 1);

-- 内置角色：超级管理员/租户管理员/运维人员/维修人员
INSERT INTO `sys_role` (`id`, `role_name`, `role_key`, `role_sort`) VALUES
(1, '超级管理员', 'super_admin', 1),
(2, '租户管理员', 'tenant_admin', 2),
(3, '运维人员', 'ops', 3),
(4, '维修人员', 'repairer', 4);

-- 用户-角色：admin 绑定超级管理员
INSERT INTO `sys_user_role` (`user_id`, `role_id`) VALUES (1, 1);
