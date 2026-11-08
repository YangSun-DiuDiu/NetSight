# 维修人员账号绑定改造实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让运维工单的维修人员与系统用户建立一对一账号绑定，使内聘人员可从用户管理选人绑定、外聘人员建档时自动建号，并保证两类人员都能用手机号登录 H5 接单。

**Architecture:** `repairer` 表新增 `user_id` 与 `repairer_type`，`user_id` 作为维修档案与 `sys_user` 的唯一关联；内聘只追加 `repairer` 角色并绑定已有用户，外聘在同一事务内创建 `sys_user` 与 `repairer`。H5 登录、移动端当前维修员解析、派单校验全部改为按 `user_id` 识别，手机号只用于短信验证码与通知；存量 `user_id IS NULL` 档案保留“同租户手机号唯一命中”兼容分支。

**Tech Stack:** Java 21、Spring Boot 4.0.8、MyBatis-Plus 3.5.17、MySQL 8、Redis、JWT（jjwt 0.12.6）、Vue 2 + Element UI、Vant（H5）。

**Spec:** `docs/维修人员账号绑定改造方案.md`

## Global Constraints

- 全程中文：交互、错误提示、文档说明、代码中用户可见文案、commit message 都用中文；类名、接口路径、变量名保持英文。
- 仓库根目录固定为 `D:\gitee\gitee\NetSight1.0`，后端模块为 `netsight-server`，前端模块为 `netsight-ui`。
- `repairer.user_id` 在数据库允许 `NULL`，但新产生的内聘/外聘档案在应用层必须写入非空 `user_id`；不批量迁移存量数据。
- `(tenant_id, user_id)` 唯一；同一租户同一手机号不允许出现两个维修档案；禁止用 `LIMIT 1` 掩盖手机号歧义。
- 内聘绑定只能追加 `repairer` 角色，禁止调用“先清后插”的 `bindRoles` 清空用户原有角色。
- 外聘建号与建档案必须在同一事务内完成；密码使用随机 10 位字符串 + BCrypt，禁止返回明文密码。
- 外聘登录名格式固定为 `ext_{tenantId}_{8位小写字母或数字}`，最多重试 5 次。
- H5 身份识别顺序固定为：JWT `repairerId` → `userId` → 存量手机号（仅 `user_id IS NULL`）；手机号只用于验证码与通知。
- 维修人员状态固定为 `1 在岗 / 0 休假 / 2 离职`；人员类型固定为 `1 内聘 / 2 外聘 / 0 存量未分类`。
- 所有维修人员写操作必须显式校验租户；超级管理员也不能绕过“用户与档案同租户”的校验。
- 测试只允许使用 `115.159.194.149` 的 `netsight` 库与 Redis；生产库 `1.94.26.126:3306/netsight-producton` 严禁用于测试。
- 后端构建固定使用 `D:\DevTools\apache-maven-3.9.6\bin\mvn.cmd`；前端构建固定使用 `D:\DevTools\node16`，不改系统 Node 22。
- 后端打包必须执行 `mvn clean package -DskipTests`；省略 `clean` 会残留已删除类的 `.class`。
- 测试环境应用目录 `/opt/nams-server/`，重启优先使用 `start_nams.sh`；禁止直接 `nohup java -jar`。若目录只有 `start.sh`，先记录实际脚本名再执行。
- Git 提交只能 `git add -- 指定路径`，禁止 `git add -A`；仓库存在无关未提交改动。
- 前端页头统计卡、操作列 `size="mini" type="text"`、删除按钮 `color:#f56c6c`、状态 `el-tag size="mini"` 沿用 AGENTS.md 的统一规范。

## File Structure

| 文件 | 责任 |
|---|---|
| `netsight-server/sql/netsight-upgrade-v1.3.1-repairer-user-binding.sql` | 新增 `user_id`、`repairer_type`、唯一键与手机号索引 |
| `netsight-server/src/main/java/com/netsight/modules/workorder/entity/Repairer.java` | 实体增加绑定字段与列表回填字段 |
| `netsight-server/src/main/java/com/netsight/modules/workorder/constant/RepairerType.java` | 人员类型常量 |
| `netsight-server/src/main/java/com/netsight/modules/workorder/service/RepairerAccountResolver.java` | 统一按 `repairerId`/`userId`/存量手机号解析维修档案 |
| `netsight-server/src/main/java/com/netsight/modules/workorder/dto/RepairerSaveResult.java` | 新增/补建返回体 |
| `netsight-server/src/main/java/com/netsight/modules/workorder/dto/RepairerAccountRequest.java` | 补建账号请求体 |
| `netsight-server/src/main/java/com/netsight/modules/system/dto/SysUserOption.java` | 选人候选返回体 |
| `netsight-server/src/main/java/com/netsight/modules/system/mapper/SysUserMapper.java` | 选人候选查询与计数 |
| `netsight-server/src/main/java/com/netsight/modules/system/service/SysUserService.java` | 外聘建号、追加角色、资料同步、删除保护 |
| `netsight-server/src/main/java/com/netsight/modules/workorder/service/RepairerService.java` | 内聘绑定、外聘建号、补建、修改/删除联动、列表回填 |
| `netsight-server/src/main/java/com/netsight/modules/workorder/controller/RepairerController.java` | 7 个维修人员接口 |
| `netsight-server/src/main/java/com/netsight/modules/system/service/AuthService.java` | H5 登录按 `user_id` 解析档案并校验状态 |
| `netsight-server/src/main/java/com/netsight/framework/security/LoginUser.java` | 增加 `repairerId` |
| `netsight-server/src/main/java/com/netsight/framework/security/JwtUtils.java` | JWT 写入 `repairerId` |
| `netsight-server/src/main/java/com/netsight/framework/security/JwtAuthenticationFilter.java` | JWT 恢复 `repairerId` |
| `netsight-server/src/main/java/com/netsight/modules/workorder/controller/MobileWorkOrderController.java` | `currentRepairer()` 改用统一解析器 |
| `netsight-server/src/main/java/com/netsight/modules/workorder/service/WorkOrderService.java` | 派单前校验维修员账号存在且启用 |
| `netsight-ui/src/api/workorder/repairer.js` | 新增选人与补建接口 |
| `netsight-ui/src/views/workorder/repairer/components/UserPickerDialog.vue` | 用户管理选人弹窗 |
| `netsight-ui/src/views/workorder/repairer/index.vue` | 列表类型标签、新增来源、补建入口、状态离职 |

---

### Task 1: 数据模型与维修档案解析器

**Files:**
- Create: `netsight-server/sql/netsight-upgrade-v1.3.1-repairer-user-binding.sql`
- Create: `netsight-server/src/main/java/com/netsight/modules/workorder/constant/RepairerType.java`
- Modify: `netsight-server/src/main/java/com/netsight/modules/workorder/entity/Repairer.java`
- Create: `netsight-server/src/main/java/com/netsight/modules/workorder/service/RepairerAccountResolver.java`
- Test: `netsight-server/src/test/java/com/netsight/modules/workorder/service/RepairerAccountResolverTest.java`

**Interfaces:**
- Consumes: `RepairerMapper`（`BaseMapper<Repairer>`）
- Produces:
  - `Repairer.getUserId(): Long`、`Repairer.setUserId(Long)`
  - `Repairer.getRepairerType(): Integer`、`Repairer.setRepairerType(Integer)`
  - `Repairer.getLoginUsername(): String`、`Repairer.setLoginUsername(String)`
  - `Repairer.getUserStatus(): Integer`、`Repairer.setUserStatus(Integer)`
  - `RepairerType.INTERNAL = 1`、`RepairerType.EXTERNAL = 2`、`RepairerType.UNCLASSIFIED = 0`
  - `RepairerAccountResolver.listBound(Long tenantId, Long userId): List<Repairer>`
  - `RepairerAccountResolver.listLegacyByPhone(Long tenantId, String phone): List<Repairer>`
  - `RepairerAccountResolver.findByIdAndTenant(Long id, Long tenantId): Repairer`
  - `RepairerAccountResolver.resolve(LoginUser loginUser): Repairer`
  - `RepairerAccountResolver.assertUsable(Repairer repairer): void`

- [ ] **Step 1: 写失败测试**

```java
package com.netsight.modules.workorder.service;

import com.netsight.common.exception.ServiceException;
import com.netsight.framework.security.LoginUser;
import com.netsight.modules.workorder.entity.Repairer;
import com.netsight.modules.workorder.mapper.RepairerMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RepairerAccountResolverTest {

    @Mock
    private RepairerMapper repairerMapper;

    @InjectMocks
    private RepairerAccountResolver resolver;

    private Repairer repairer(Long id, Long tenantId, Long userId, Integer status) {
        Repairer repairer = new Repairer();
        repairer.setId(id);
        repairer.setTenantId(tenantId);
        repairer.setUserId(userId);
        repairer.setRepairerType(userId == null ? 0 : 1);
        repairer.setName("维修员甲");
        repairer.setPhone("19900000002");
        repairer.setStatus(status);
        return repairer;
    }

    private LoginUser loginUser(Long repairerId, Long userId, Long tenantId, String phone) {
        LoginUser loginUser = new LoginUser();
        loginUser.setRepairerId(repairerId);
        loginUser.setUserId(userId);
        loginUser.setTenantId(tenantId);
        loginUser.setPhone(phone);
        return loginUser;
    }

    @Test
    void resolveUsesRepairerIdClaimFirst() {
        Repairer repairer = repairer(101L, 14L, 22L, 1);
        when(repairerMapper.selectOne(any())).thenReturn(repairer);

        Repairer result = resolver.resolve(loginUser(101L, 22L, 14L, "19900000002"));

        assertEquals(101L, result.getId());
    }

    @Test
    void resolveFallsBackToUserIdWhenOldTokenHasNoRepairerId() {
        Repairer repairer = repairer(101L, 14L, 22L, 1);
        when(repairerMapper.selectList(any())).thenReturn(List.of(repairer));

        Repairer result = resolver.resolve(loginUser(null, 22L, 14L, "19900000002"));

        assertEquals(101L, result.getId());
    }

    @Test
    void resolveRejectsResignedRepairer() {
        Repairer repairer = repairer(101L, 14L, 22L, 2);
        when(repairerMapper.selectOne(any())).thenReturn(repairer);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> resolver.resolve(loginUser(101L, 22L, 14L, "19900000002")));

        assertEquals("当前维修人员已离职，不能登录 H5", exception.getMessage());
    }

    @Test
    void resolveRejectsMultipleLegacyMatches() {
        Repairer first = repairer(101L, 14L, null, 1);
        Repairer second = repairer(102L, 14L, null, 1);
        when(repairerMapper.selectList(any())).thenReturn(List.of(first, second));

        ServiceException exception = assertThrows(ServiceException.class,
                () -> resolver.resolve(loginUser(null, null, 14L, "19900000002")));

        assertEquals("手机号关联多个维修人员账号，请联系管理员处理", exception.getMessage());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```powershell
$mvn = "D:\DevTools\apache-maven-3.9.6\bin\mvn.cmd"
& $mvn -f "D:\gitee\gitee\NetSight1.0\netsight-server\pom.xml" -Dtest=RepairerAccountResolverTest test
```

Expected: 编译失败，提示 `RepairerAccountResolver` 或 `Repairer.setUserId` 不存在。

- [ ] **Step 3: 写最小实现**

`netsight-server/sql/netsight-upgrade-v1.3.1-repairer-user-binding.sql`：

```sql
USE netsight;

-- 维修人员与系统用户绑定；存量数据允许 NULL，新数据由应用层保证非空
ALTER TABLE repairer
    ADD COLUMN user_id BIGINT NULL COMMENT '绑定系统用户ID（sys_user.id）' AFTER repairer_no,
    ADD COLUMN repairer_type TINYINT NOT NULL DEFAULT 0 COMMENT '人员类型 1内聘 2外聘 0存量未分类' AFTER user_id,
    ADD UNIQUE KEY uk_repairer_user (tenant_id, user_id),
    ADD KEY idx_repairer_phone (tenant_id, phone);
```

`RepairerType.java`：

```java
package com.netsight.modules.workorder.constant;

/**
 * 维修人员来源类型。
 */
public final class RepairerType {

    /** 存量未分类 */
    public static final int UNCLASSIFIED = 0;

    /** 园区内部人员，从用户管理选人绑定 */
    public static final int INTERNAL = 1;

    /** 外聘人员，建档时自动创建 sys_user */
    public static final int EXTERNAL = 2;

    private RepairerType() {
    }
}
```

`Repairer.java` 在 `repairerNo` 后增加：

```java
    /** 绑定系统用户ID（sys_user.id） */
    private Long userId;

    /** 人员类型 1内聘 2外聘 0存量未分类 */
    private Integer repairerType;
```

在 `remark` 后增加：

```java
    /** 列表回填：登录账号（非表字段） */
    @TableField(exist = false)
    private String loginUsername;

    /** 列表回填：账号状态 1正常 0禁用（非表字段） */
    @TableField(exist = false)
    private Integer userStatus;
```

文件头补 `import com.baomidou.mybatisplus.annotation.TableField;`。

`RepairerAccountResolver.java`：

```java
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
```

- [ ] **Step 4: 运行测试确认通过**

```powershell
$mvn = "D:\DevTools\apache-maven-3.9.6\bin\mvn.cmd"
& $mvn -f "D:\gitee\gitee\NetSight1.0\netsight-server\pom.xml" -Dtest=RepairerAccountResolverTest test
```

Expected: `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`。

- [ ] **Step 5: 提交**

```powershell
git -C "D:\gitee\gitee\NetSight1.0" add -- "netsight-server/sql/netsight-upgrade-v1.3.1-repairer-user-binding.sql" "netsight-server/src/main/java/com/netsight/modules/workorder/constant/RepairerType.java" "netsight-server/src/main/java/com/netsight/modules/workorder/entity/Repairer.java" "netsight-server/src/main/java/com/netsight/modules/workorder/service/RepairerAccountResolver.java" "netsight-server/src/test/java/com/netsight/modules/workorder/service/RepairerAccountResolverTest.java"
git -C "D:\gitee\gitee\NetSight1.0" commit -m "feat: 维修人员实体增加账号绑定字段与档案解析器"
```

---

### Task 2: 用户管理侧账号能力

**Files:**
- Modify: `netsight-server/src/main/java/com/netsight/modules/system/service/SysUserService.java`
- Test: `netsight-server/src/test/java/com/netsight/modules/system/service/SysUserServiceTest.java`

**Interfaces:**
- Consumes: `SysUserMapper`、`SysRoleMapper`、`SysUserRoleMapper`、`RepairerMapper`、`PasswordEncoder`
- Produces:
  - `SysUserService.createRepairerAccount(Long tenantId, String realName, String phone): SysUser`
  - `SysUserService.ensureRole(Long userId, String roleKey): void`
  - `SysUserService.hasRole(Long userId, String roleKey): boolean`
  - `SysUserService.isBoundToRepairer(Long tenantId, Long userId): boolean`
  - `SysUserService.syncRepairerProfile(Long userId, String realName, String phone): void`
  - `SysUserService.disableRepairerAccountIfOnlyRole(Long userId): void`
  - `SysUserService.countUsersByPhoneInTenant(Long tenantId, String phone, Long excludeUserId): long`
  - `SysUserService.listUnboundUsersByPhone(Long tenantId, String phone): List<SysUser>`

- [ ] **Step 1: 写失败测试**

```java
package com.netsight.modules.system.service;

import com.netsight.common.exception.ServiceException;
import com.netsight.framework.security.LoginUser;
import com.netsight.modules.system.entity.SysRole;
import com.netsight.modules.system.entity.SysUser;
import com.netsight.modules.system.mapper.SysRoleMapper;
import com.netsight.modules.system.mapper.SysUserMapper;
import com.netsight.modules.system.mapper.SysUserRoleMapper;
import com.netsight.modules.workorder.mapper.RepairerMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SysUserServiceTest {

    @Mock private SysUserMapper sysUserMapper;
    @Mock private SysRoleMapper sysRoleMapper;
    @Mock private SysUserRoleMapper sysUserRoleMapper;
    @Mock private RepairerMapper repairerMapper;
    @Mock private PasswordEncoder passwordEncoder;

    private SysUserService service;

    @BeforeEach
    void setUp() {
        service = new SysUserService(sysUserMapper, sysRoleMapper, sysUserRoleMapper,
                repairerMapper, passwordEncoder);
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(21L);
        loginUser.setTenantId(14L);
        loginUser.setIsSuperAdmin(true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private SysUser user(Long id, Long tenantId, String realName, String phone, Integer status) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setTenantId(tenantId);
        user.setUsername("u" + id);
        user.setRealName(realName);
        user.setPhone(phone);
        user.setStatus(status);
        return user;
    }

    private SysRole role(Long id, String roleKey) {
        SysRole role = new SysRole();
        role.setId(id);
        role.setRoleKey(roleKey);
        role.setStatus(1);
        return role;
    }

    @Test
    void createRepairerAccountCreatesUserWithRepairerRole() {
        when(sysUserMapper.selectCount(any())).thenReturn(0L);
        when(passwordEncoder.encode(any())).thenReturn("bcrypt-password");
        when(sysRoleMapper.selectOne(any())).thenReturn(role(4L, "repairer"));
        when(sysUserRoleMapper.selectRoleIdsByUserId(99L)).thenReturn(List.of());
        when(sysUserMapper.insert(any())).thenAnswer(invocation -> {
            SysUser inserted = invocation.getArgument(0);
            inserted.setId(99L);
            return 1;
        });

        SysUser created = service.createRepairerAccount(14L, "张三", "19900000009");

        assertEquals(99L, created.getId());
        assertTrue(created.getUsername().matches("ext_14_[a-z0-9]{8}"));
        verify(sysUserRoleMapper).insertUserRole(99L, 4L);
    }

    @Test
    void ensureRoleIsIdempotent() {
        when(sysRoleMapper.selectOne(any())).thenReturn(role(4L, "repairer"));
        when(sysUserRoleMapper.selectRoleIdsByUserId(22L)).thenReturn(List.of(4L));

        service.ensureRole(22L, "repairer");

        verify(sysUserRoleMapper, org.mockito.Mockito.never()).insertUserRole(22L, 4L);
    }

    @Test
    void updateUserSyncsBoundRepairerProfile() {
        SysUser existing = user(22L, 14L, "维修员甲", "19900000002", 1);
        SysUser update = user(22L, 14L, "维修员甲改", "19900000008", 1);
        update.setRoleIds(List.of(3L));
        when(sysUserMapper.selectById(22L)).thenReturn(existing);
        when(sysUserMapper.selectCount(any())).thenReturn(0L);
        when(repairerMapper.selectCount(any())).thenReturn(1L, 0L);
        when(sysRoleMapper.selectOne(any())).thenReturn(role(4L, "repairer"));

        service.updateUser(update);

        verify(repairerMapper).update(isNull(), any());
        verify(sysUserRoleMapper).insertUserRole(22L, 3L);
        verify(sysUserRoleMapper).insertUserRole(22L, 4L);
    }

    @Test
    void deleteUserRejectsUserBoundToRepairer() {
        when(sysUserMapper.selectById(22L)).thenReturn(user(22L, 14L, "维修员甲", "19900000002", 1));
        when(sysRoleMapper.selectOne(any())).thenReturn(null);
        when(repairerMapper.selectCount(any())).thenReturn(1L);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.deleteUser(22L));

        assertEquals("该用户已绑定维修人员档案，请先删除维修人员档案", exception.getMessage());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```powershell
$mvn = "D:\DevTools\apache-maven-3.9.6\bin\mvn.cmd"
& $mvn -f "D:\gitee\gitee\NetSight1.0\netsight-server\pom.xml" -Dtest=SysUserServiceTest test
```

Expected: 编译失败，提示构造器参数不匹配或 `createRepairerAccount` 不存在。

- [ ] **Step 3: 写最小实现**

`SysUserService` 在 `passwordEncoder` 字段前增加（构造器参数顺序保持为 `sysUserMapper, sysRoleMapper, sysUserRoleMapper, repairerMapper, passwordEncoder`）：

```java
    private final RepairerMapper repairerMapper;

    private static final String EXTERNAL_USERNAME_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";
```

增加 import：

```java
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.netsight.modules.workorder.entity.Repairer;
import com.netsight.modules.workorder.mapper.RepairerMapper;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;
```

在 `getRoleKeys` 后增加：

```java
    /**
     * 外聘维修人员建号：随机登录名 + 随机密码（BCrypt）+ repairer 角色。
     * 密码不返回、不落日志，H5 只走手机号验证码登录。
     */
    @Transactional(rollbackFor = Exception.class)
    public SysUser createRepairerAccount(Long tenantId, String realName, String phone) {
        SysUser user = new SysUser();
        user.setTenantId(tenantId);
        user.setRealName(realName);
        user.setPhone(phone);
        user.setStatus(1);
        user.setUsername(generateExternalUsername(tenantId));
        String rawPassword = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        user.setPassword(passwordEncoder.encode(rawPassword));
        sysUserMapper.insert(user);
        ensureRole(user.getId(), "repairer");
        log.info("外聘维修人员账号已创建 tenantId={} userId={} username={}",
                tenantId, user.getId(), user.getUsername());
        return user;
    }

    /**
     * 追加单个角色，不清空用户原有角色。
     */
    public void ensureRole(Long userId, String roleKey) {
        Long roleId = requireRoleId(roleKey);
        List<Long> roleIds = sysUserRoleMapper.selectRoleIdsByUserId(userId);
        if (roleIds != null && roleIds.contains(roleId)) {
            return;
        }
        sysUserRoleMapper.insertUserRole(userId, roleId);
    }

    public boolean hasRole(Long userId, String roleKey) {
        List<String> roleKeys = sysUserRoleMapper.selectRoleKeysByUserId(userId);
        return roleKeys != null && roleKeys.contains(roleKey);
    }

    public boolean isBoundToRepairer(Long tenantId, Long userId) {
        if (tenantId == null || userId == null) {
            return false;
        }
        return repairerMapper.selectCount(new LambdaQueryWrapper<Repairer>()
                .eq(Repairer::getTenantId, tenantId)
                .eq(Repairer::getUserId, userId)) > 0;
    }

    public void syncRepairerProfile(Long userId, String realName, String phone) {
        repairerMapper.update(null, new LambdaUpdateWrapper<Repairer>()
                .eq(Repairer::getUserId, userId)
                .set(Repairer::getName, realName)
                .set(Repairer::getPhone, phone));
    }

    /**
     * 删除外聘档案后处理账号：账号角色只有 repairer 时禁用，有其他角色时保留并记日志。
     */
    public void disableRepairerAccountIfOnlyRole(Long userId) {
        List<String> roleKeys = getRoleKeys(userId);
        if (roleKeys != null && roleKeys.size() == 1 && roleKeys.contains("repairer")) {
            SysUser update = new SysUser();
            update.setId(userId);
            update.setStatus(0);
            sysUserMapper.updateById(update);
            log.info("外聘维修人员账号已禁用 userId={}", userId);
            return;
        }
        log.info("外聘维修人员账号保留，存在其他角色 userId={} roles={}", userId, roleKeys);
    }

    public long countUsersByPhoneInTenant(Long tenantId, String phone, Long excludeUserId) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getTenantId, tenantId)
                .eq(SysUser::getPhone, phone);
        if (excludeUserId != null) {
            wrapper.ne(SysUser::getId, excludeUserId);
        }
        return sysUserMapper.selectCount(wrapper);
    }

    public List<SysUser> listUnboundUsersByPhone(Long tenantId, String phone) {
        List<SysUser> users = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getTenantId, tenantId)
                .eq(SysUser::getPhone, phone)
                .eq(SysUser::getStatus, 1)
                .orderByAsc(SysUser::getId));
        if (users.isEmpty()) {
            return List.of();
        }
        List<Long> userIds = users.stream().map(SysUser::getId).toList();
        Set<Long> boundUserIds = repairerMapper.selectList(new LambdaQueryWrapper<Repairer>()
                        .eq(Repairer::getTenantId, tenantId)
                        .in(Repairer::getUserId, userIds))
                .stream()
                .map(Repairer::getUserId)
                .collect(Collectors.toSet());
        return users.stream()
                .filter(user -> !boundUserIds.contains(user.getId()))
                .toList();
    }

    private Long requireRoleId(String roleKey) {
        SysRole role = sysRoleMapper.selectOne(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getRoleKey, roleKey)
                .eq(SysRole::getStatus, 1)
                .last("LIMIT 1"));
        if (role == null) {
            throw new ServiceException(500, "维修人员角色未配置，请联系管理员");
        }
        return role.getId();
    }

    private String generateExternalUsername(Long tenantId) {
        SecureRandom random = new SecureRandom();
        for (int attempt = 0; attempt < 5; attempt++) {
            StringBuilder suffix = new StringBuilder(8);
            for (int i = 0; i < 8; i++) {
                suffix.append(EXTERNAL_USERNAME_CHARS.charAt(
                        random.nextInt(EXTERNAL_USERNAME_CHARS.length())));
            }
            String username = "ext_" + tenantId + "_" + suffix;
            if (sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getUsername, username)) == 0) {
                return username;
            }
        }
        throw new ServiceException(500, "登录账号生成失败，请重试");
    }
```

`updateUser` 方法签名补 `@Transactional(rollbackFor = Exception.class)`（用户资料与维修档案同步必须同事务），然后把 `sysUserMapper.updateById(update);` 到 `bindRoles(user.getId(), user.getRoleIds());` 的两行替换为：

```java
        boolean bound = isBoundToRepairer(exist.getTenantId(), user.getId());
        if (bound && user.getRealName() != null && !StringUtils.hasText(user.getRealName())) {
            throw new ServiceException(500, "已绑定维修人员的用户必须维护姓名");
        }
        if (bound && user.getPhone() != null && !StringUtils.hasText(user.getPhone())) {
            throw new ServiceException(500, "已绑定维修人员的用户必须维护手机号");
        }
        if (bound && user.getPhone() != null && !Objects.equals(exist.getPhone(), user.getPhone())) {
            if (countUsersByPhoneInTenant(exist.getTenantId(), user.getPhone(), user.getId()) > 0) {
                throw new ServiceException(500, "该手机号已被其他用户使用");
            }
            if (repairerMapper.selectCount(new LambdaQueryWrapper<Repairer>()
                    .eq(Repairer::getTenantId, exist.getTenantId())
                    .eq(Repairer::getPhone, user.getPhone())) > 0) {
                throw new ServiceException(500, "该手机号已存在其他维修人员档案");
            }
        }
        boolean profileChanged =
                (user.getRealName() != null && !Objects.equals(exist.getRealName(), user.getRealName()))
                        || (user.getPhone() != null && !Objects.equals(exist.getPhone(), user.getPhone()));
        if (profileChanged) {
            String realName = user.getRealName() != null ? user.getRealName() : exist.getRealName();
            String phone = user.getPhone() != null ? user.getPhone() : exist.getPhone();
            syncRepairerProfile(user.getId(), realName, phone);
        }
        sysUserMapper.updateById(update);
        List<Long> currentRoleIds = sysUserRoleMapper.selectRoleIdsByUserId(user.getId());
        List<Long> roleIds = user.getRoleIds() == null
                ? (currentRoleIds == null ? new ArrayList<>() : new ArrayList<>(currentRoleIds))
                : new ArrayList<>(user.getRoleIds());
        if (bound) {
            Long repairerRoleId = requireRoleId("repairer");
            if (!roleIds.contains(repairerRoleId)) {
                roleIds.add(repairerRoleId);
            }
        }
        bindRoles(user.getId(), roleIds);
```

`deleteUser` 在 `isSuperAdminUser` 校验后增加：

```java
        if (isBoundToRepairer(exist.getTenantId(), userId)) {
            throw new ServiceException(500, "该用户已绑定维修人员档案，请先删除维修人员档案");
        }
```

- [ ] **Step 4: 运行测试确认通过**

```powershell
$mvn = "D:\DevTools\apache-maven-3.9.6\bin\mvn.cmd"
& $mvn -f "D:\gitee\gitee\NetSight1.0\netsight-server\pom.xml" -Dtest=SysUserServiceTest test
```

Expected: `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`。

- [ ] **Step 5: 提交**

```powershell
git -C "D:\gitee\gitee\NetSight1.0" add -- "netsight-server/src/main/java/com/netsight/modules/system/service/SysUserService.java" "netsight-server/src/test/java/com/netsight/modules/system/service/SysUserServiceTest.java"
git -C "D:\gitee\gitee\NetSight1.0" commit -m "feat: 用户管理支持外聘建号与维修档案联动"
```

---

### Task 3: 维修人员接口与联动

**Files:**
- Create: `netsight-server/src/main/java/com/netsight/modules/system/dto/SysUserOption.java`
- Create: `netsight-server/src/main/java/com/netsight/modules/workorder/dto/RepairerSaveResult.java`
- Create: `netsight-server/src/main/java/com/netsight/modules/workorder/dto/RepairerAccountRequest.java`
- Modify: `netsight-server/src/main/java/com/netsight/modules/system/mapper/SysUserMapper.java`
- Modify: `netsight-server/src/main/java/com/netsight/modules/workorder/service/RepairerService.java`
- Modify: `netsight-server/src/main/java/com/netsight/modules/workorder/controller/RepairerController.java`
- Test: `netsight-server/src/test/java/com/netsight/modules/workorder/service/RepairerServiceTest.java`

**Interfaces:**
- Consumes:
  - `SysUserService.createRepairerAccount(Long, String, String): SysUser`
  - `SysUserService.ensureRole(Long, String): void`
  - `SysUserService.hasRole(Long, String): boolean`
  - `SysUserService.countUsersByPhoneInTenant(Long, String, Long): long`
  - `SysUserService.listUnboundUsersByPhone(Long, String): List<SysUser>`
  - `SysUserService.disableRepairerAccountIfOnlyRole(Long): void`
- Produces:
  - `RepairerService.pageRepairer(long, long, String keyword, String name, Integer status): PageResult<Repairer>`
  - `RepairerService.pageUserOptions(long, long, String): PageResult<SysUserOption>`
  - `RepairerService.addRepairer(Repairer): RepairerSaveResult`
  - `RepairerService.bindAccount(Long, Integer): RepairerSaveResult`
  - `RepairerService.updateRepairer(Repairer): void`
  - `RepairerService.removeRepairer(Long): void`
  - `RepairerController` 新增 `GET /workorder/repairer/user-options` 与 `POST /workorder/repairer/{id}/account`

- [ ] **Step 1: 写失败测试**

```java
package com.netsight.modules.workorder.service;

import com.netsight.common.exception.ServiceException;
import com.netsight.framework.security.LoginUser;
import com.netsight.modules.system.entity.SysUser;
import com.netsight.modules.system.mapper.SysUserMapper;
import com.netsight.modules.system.service.SysUserService;
import com.netsight.modules.workorder.dto.RepairerSaveResult;
import com.netsight.modules.workorder.entity.Repairer;
import com.netsight.modules.workorder.mapper.RepairerMapper;
import com.netsight.modules.workorder.mapper.WorkOrderMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RepairerServiceTest {

    @Mock private RepairerMapper repairerMapper;
    @Mock private WorkOrderMapper workOrderMapper;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private SysUserService sysUserService;

    private RepairerService service;

    @BeforeEach
    void setUp() {
        service = new RepairerService(repairerMapper, workOrderMapper, sysUserMapper, sysUserService);
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(21L);
        loginUser.setTenantId(14L);
        loginUser.setIsSuperAdmin(false);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private SysUser sysUser(Long id, String realName, String phone, Integer status) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setTenantId(14L);
        user.setUsername("user" + id);
        user.setRealName(realName);
        user.setPhone(phone);
        user.setStatus(status);
        return user;
    }

    private Repairer repairerRequest(int type, Long userId, String name, String phone) {
        Repairer repairer = new Repairer();
        repairer.setRepairerType(type);
        repairer.setUserId(userId);
        repairer.setName(name);
        repairer.setPhone(phone);
        repairer.setStatus(1);
        repairer.setDeviceTypes("[\"network\"]");
        return repairer;
    }

    @Test
    void addInternalRepairerBindsExistingUserAndAppendsRole() {
        SysUser user = sysUser(22L, "维修员甲", "19900000002", 1);
        when(sysUserMapper.selectByIdIgnoreTenant(22L)).thenReturn(user);
        when(repairerMapper.selectCount(any())).thenReturn(0L);
        when(repairerMapper.insert(any())).thenAnswer(invocation -> {
            Repairer inserted = invocation.getArgument(0);
            inserted.setId(101L);
            return 1;
        });

        RepairerSaveResult result = service.addRepairer(repairerRequest(1, 22L, null, null));

        ArgumentCaptor<Repairer> captor = ArgumentCaptor.forClass(Repairer.class);
        verify(repairerMapper).insert(captor.capture());
        Repairer inserted = captor.getValue();
        assertEquals(14L, inserted.getTenantId());
        assertEquals(22L, inserted.getUserId());
        assertEquals(1, inserted.getRepairerType());
        assertEquals("维修员甲", inserted.getName());
        assertEquals("19900000002", inserted.getPhone());
        assertEquals(101L, result.getId());
        assertEquals("user22", result.getLoginUsername());
        assertFalse(result.isAccountCreated());
        verify(sysUserService).ensureRole(22L, "repairer");
    }

    @Test
    void addInternalRepairerRejectsDuplicateBinding() {
        SysUser user = sysUser(22L, "维修员甲", "19900000002", 1);
        when(sysUserMapper.selectByIdIgnoreTenant(22L)).thenReturn(user);
        when(repairerMapper.selectCount(any())).thenReturn(1L);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.addRepairer(repairerRequest(1, 22L, null, null)));

        assertEquals("该用户已绑定其他维修人员档案", exception.getMessage());
    }

    @Test
    void addExternalRepairerCreatesAccountAndRepairer() {
        SysUser created = sysUser(99L, "张三", "19900000009", 1);
        created.setUsername("ext_14_ab12cd34");
        when(sysUserService.countUsersByPhoneInTenant(14L, "19900000009", null)).thenReturn(0L);
        when(repairerMapper.selectCount(any())).thenReturn(0L);
        when(sysUserService.createRepairerAccount(14L, "张三", "19900000009")).thenReturn(created);
        when(repairerMapper.insert(any())).thenAnswer(invocation -> {
            Repairer inserted = invocation.getArgument(0);
            inserted.setId(102L);
            return 1;
        });

        RepairerSaveResult result = service.addRepairer(
                repairerRequest(2, null, "张三", "19900000009"));

        assertEquals(102L, result.getId());
        assertEquals("ext_14_ab12cd34", result.getLoginUsername());
        assertTrue(result.isAccountCreated());
    }

    @Test
    void addExternalRepairerRejectsOccupiedPhone() {
        when(sysUserService.countUsersByPhoneInTenant(14L, "19900000009", null)).thenReturn(1L);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.addRepairer(repairerRequest(2, null, "张三", "19900000009")));

        assertEquals("该手机号已被本租户用户占用，请改用内聘选择", exception.getMessage());
    }

    @Test
    void bindAccountForInternalRepairerUsesUnboundSamePhoneUser() {
        Repairer existing = new Repairer();
        existing.setId(101L);
        existing.setTenantId(14L);
        existing.setRepairerType(0);
        existing.setName("维修员甲");
        existing.setPhone("19900000002");
        existing.setStatus(1);
        SysUser user = sysUser(22L, "维修员甲", "19900000002", 1);
        when(repairerMapper.selectOne(any())).thenReturn(existing);
        when(sysUserService.listUnboundUsersByPhone(14L, "19900000002")).thenReturn(List.of(user));

        RepairerSaveResult result = service.bindAccount(101L, 1);

        assertEquals(101L, result.getId());
        assertEquals("user22", result.getLoginUsername());
        assertFalse(result.isAccountCreated());
        verify(repairerMapper).updateById(existing);
        verify(sysUserService).ensureRole(22L, "repairer");
    }

    @Test
    void removeExternalRepairerDisablesAccountWhenOnlyRepairerRole() {
        Repairer existing = new Repairer();
        existing.setId(102L);
        existing.setTenantId(14L);
        existing.setRepairerType(2);
        existing.setUserId(99L);
        existing.setStatus(1);
        when(repairerMapper.selectOne(any())).thenReturn(existing);

        service.removeRepairer(102L);

        verify(sysUserService).disableRepairerAccountIfOnlyRole(99L);
        verify(repairerMapper).deleteById(102L);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```powershell
$mvn = "D:\DevTools\apache-maven-3.9.6\bin\mvn.cmd"
& $mvn -f "D:\gitee\gitee\NetSight1.0\netsight-server\pom.xml" -Dtest=RepairerServiceTest test
```

Expected: 编译失败，提示构造器参数不匹配或 `bindAccount` 不存在。

- [ ] **Step 3: 写最小实现**

`SysUserOption.java`：

```java
package com.netsight.modules.system.dto;

import lombok.Data;

/**
 * 维修人员新增页的选人候选，只暴露姓名、手机号、登录账号。
 */
@Data
public class SysUserOption {

    private Long id;
    private String username;
    private String realName;
    private String phone;
}
```

`RepairerSaveResult.java`：

```java
package com.netsight.modules.workorder.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 维修人员新增/补建账号返回体。
 */
@Data
@AllArgsConstructor
public class RepairerSaveResult {

    private Long id;
    private String loginUsername;
    private boolean accountCreated;
}
```

`RepairerAccountRequest.java`：

```java
package com.netsight.modules.workorder.dto;

import lombok.Data;

/**
 * 补建账号请求体：1 内聘绑定同手机号用户，2 外聘创建新账号。
 */
@Data
public class RepairerAccountRequest {

    private Integer repairerType;
}
```

`SysUserMapper.java` 增加：

```java
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.netsight.modules.system.dto.SysUserOption;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
```

```java
    /**
     * 按 id 查询用户并跳过租户拦截，用于“跨租户绑定”给出准确提示。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM sys_user WHERE id = #{id} AND del_flag = 0")
    SysUser selectByIdIgnoreTenant(@Param("id") Long id);

    /**
     * 选人候选：本租户、启用、有手机号、未绑定维修档案、非 super_admin。
     */
    @Select("""
            SELECT u.id, u.username, u.real_name AS realName, u.phone
            FROM sys_user u
            WHERE u.del_flag = 0
              AND u.status = 1
              AND u.tenant_id = #{tenantId}
              AND u.phone IS NOT NULL AND u.phone <> ''
              AND NOT EXISTS (
                  SELECT 1 FROM repairer rp
                  WHERE rp.tenant_id = u.tenant_id AND rp.user_id = u.id AND rp.del_flag = 0
              )
              AND NOT EXISTS (
                  SELECT 1 FROM sys_user_role ur
                  INNER JOIN sys_role r ON r.id = ur.role_id
                  WHERE ur.user_id = u.id AND r.role_key = 'super_admin'
                    AND r.del_flag = 0 AND r.status = 1
              )
              AND (u.real_name LIKE CONCAT('%', IFNULL(#{keyword}, ''), '%')
                OR u.phone LIKE CONCAT('%', IFNULL(#{keyword}, ''), '%')
                OR u.username LIKE CONCAT('%', IFNULL(#{keyword}, ''), '%'))
            ORDER BY u.id DESC
            LIMIT #{offset}, #{pageSize}
            """)
    List<SysUserOption> selectRepairerUserOptions(@Param("tenantId") Long tenantId,
                                                   @Param("keyword") String keyword,
                                                   @Param("offset") long offset,
                                                   @Param("pageSize") long pageSize);

    @Select("""
            SELECT COUNT(1)
            FROM sys_user u
            WHERE u.del_flag = 0
              AND u.status = 1
              AND u.tenant_id = #{tenantId}
              AND u.phone IS NOT NULL AND u.phone <> ''
              AND NOT EXISTS (
                  SELECT 1 FROM repairer rp
                  WHERE rp.tenant_id = u.tenant_id AND rp.user_id = u.id AND rp.del_flag = 0
              )
              AND NOT EXISTS (
                  SELECT 1 FROM sys_user_role ur
                  INNER JOIN sys_role r ON r.id = ur.role_id
                  WHERE ur.user_id = u.id AND r.role_key = 'super_admin'
                    AND r.del_flag = 0 AND r.status = 1
              )
              AND (u.real_name LIKE CONCAT('%', IFNULL(#{keyword}, ''), '%')
                OR u.phone LIKE CONCAT('%', IFNULL(#{keyword}, ''), '%')
                OR u.username LIKE CONCAT('%', IFNULL(#{keyword}, ''), '%'))
            """)
    long countRepairerUserOptions(@Param("tenantId") Long tenantId,
                                  @Param("keyword") String keyword);
```

`RepairerService.java` 完整替换为：

```java
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
            if (existing.getUserId() != null) {
                sysUserService.syncRepairerProfile(existing.getUserId(), name, phone);
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
```

`RepairerController.java` 完整替换为：

```java
package com.netsight.modules.workorder.controller;

import com.netsight.common.core.PageResult;
import com.netsight.common.core.R;
import com.netsight.framework.aspectj.Log;
import com.netsight.modules.system.dto.SysUserOption;
import com.netsight.modules.workorder.dto.RepairerAccountRequest;
import com.netsight.modules.workorder.dto.RepairerSaveResult;
import com.netsight.modules.workorder.entity.Repairer;
import com.netsight.modules.workorder.service.RepairerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 维修人员库控制器（运维工单 - 维修人员）。
 */
@Slf4j
@RestController
@RequestMapping("/workorder/repairer")
@RequiredArgsConstructor
public class RepairerController {

    private final RepairerService repairerService;

    @PreAuthorize("hasRole('super_admin') or hasAuthority('workorder:repairer:list')")
    @GetMapping("/list")
    public R<PageResult<Repairer>> list(@RequestParam(defaultValue = "1") long pageNum,
                                        @RequestParam(defaultValue = "10") long pageSize,
                                        @RequestParam(required = false) String keyword,
                                        @RequestParam(required = false) String name,
                                        @RequestParam(required = false) Integer status) {
        return R.ok(repairerService.pageRepairer(pageNum, pageSize, keyword, name, status));
    }

    @PreAuthorize("hasRole('super_admin') or hasAuthority('workorder:repairer:list')")
    @GetMapping("/options")
    public R<List<Repairer>> options(@RequestParam(required = false) Long workOrderId) {
        return R.ok(repairerService.listOptions(workOrderId));
    }

    @PreAuthorize("hasRole('super_admin') or hasAuthority('workorder:repairer:add')")
    @GetMapping("/user-options")
    public R<PageResult<SysUserOption>> userOptions(@RequestParam(defaultValue = "1") long pageNum,
                                                    @RequestParam(defaultValue = "10") long pageSize,
                                                    @RequestParam(required = false) String keyword) {
        return R.ok(repairerService.pageUserOptions(pageNum, pageSize, keyword));
    }

    @PreAuthorize("hasRole('super_admin') or hasAuthority('workorder:repairer:add')")
    @PostMapping
    @Log(module = "维修人员", action = "新增维修人员")
    public R<RepairerSaveResult> add(@RequestBody Repairer repairer) {
        return R.ok("维修人员已添加", repairerService.addRepairer(repairer));
    }

    @PreAuthorize("hasRole('super_admin') or hasAuthority('workorder:repairer:edit')")
    @PostMapping("/{id}/account")
    @Log(module = "维修人员", action = "补建账号")
    public R<RepairerSaveResult> bindAccount(@PathVariable Long id,
                                             @RequestBody RepairerAccountRequest request) {
        return R.ok("账号已绑定",
                repairerService.bindAccount(id, request.getRepairerType()));
    }

    @PreAuthorize("hasRole('super_admin') or hasAuthority('workorder:repairer:edit')")
    @PutMapping
    @Log(module = "维修人员", action = "修改维修人员")
    public R<Void> update(@RequestBody Repairer repairer) {
        repairerService.updateRepairer(repairer);
        return R.ok();
    }

    @PreAuthorize("hasRole('super_admin') or hasAuthority('workorder:repairer:remove')")
    @DeleteMapping("/{id}")
    @Log(module = "维修人员", action = "删除维修人员")
    public R<Void> remove(@PathVariable Long id) {
        repairerService.removeRepairer(id);
        return R.ok();
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```powershell
$mvn = "D:\DevTools\apache-maven-3.9.6\bin\mvn.cmd"
& $mvn -f "D:\gitee\gitee\NetSight1.0\netsight-server\pom.xml" -Dtest=RepairerServiceTest test
```

Expected: `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`。

- [ ] **Step 5: 提交**

```powershell
git -C "D:\gitee\gitee\NetSight1.0" add -- "netsight-server/src/main/java/com/netsight/modules/system/dto/SysUserOption.java" "netsight-server/src/main/java/com/netsight/modules/workorder/dto/RepairerSaveResult.java" "netsight-server/src/main/java/com/netsight/modules/workorder/dto/RepairerAccountRequest.java" "netsight-server/src/main/java/com/netsight/modules/system/mapper/SysUserMapper.java" "netsight-server/src/main/java/com/netsight/modules/workorder/service/RepairerService.java" "netsight-server/src/main/java/com/netsight/modules/workorder/controller/RepairerController.java" "netsight-server/src/test/java/com/netsight/modules/workorder/service/RepairerServiceTest.java"
git -C "D:\gitee\gitee\NetSight1.0" commit -m "feat: 维修人员支持内聘绑定与外聘建号"
```

---

### Task 4: H5 登录与 JWT 携带维修档案 ID

**Files:**
- Modify: `netsight-server/src/main/java/com/netsight/framework/security/LoginUser.java`
- Modify: `netsight-server/src/main/java/com/netsight/framework/security/JwtUtils.java`
- Modify: `netsight-server/src/main/java/com/netsight/framework/security/JwtAuthenticationFilter.java`
- Modify: `netsight-server/src/main/java/com/netsight/modules/system/service/AuthService.java`
- Test: `netsight-server/src/test/java/com/netsight/modules/system/service/AuthServiceTest.java`
- Test: `netsight-server/src/test/java/com/netsight/framework/security/JwtUtilsTest.java`

**Interfaces:**
- Consumes:
  - `RepairerAccountResolver.listBound(Long, Long): List<Repairer>`
  - `RepairerAccountResolver.listLegacyByPhone(Long, String): List<Repairer>`
  - `RepairerAccountResolver.assertUsable(Repairer): void`
- Produces:
  - `LoginUser.getRepairerId(): Long`、`LoginUser.setRepairerId(Long)`
  - JWT claim `repairerId`
  - `AuthService.loginByPhone(String, String, String): Map<String, Object>`，H5 登录结果中的 `user` 带 `repairerId`

- [ ] **Step 1: 写失败测试**

```java
package com.netsight.modules.system.service;

import com.netsight.common.core.ResultCode;
import com.netsight.common.exception.ServiceException;
import com.netsight.framework.security.JwtUtils;
import com.netsight.framework.security.LoginUser;
import com.netsight.modules.alert.channel.ChannelRegistry;
import com.netsight.modules.alert.service.NotifyChannelService;
import com.netsight.modules.system.entity.SysUser;
import com.netsight.modules.system.mapper.SysRolePermissionMapper;
import com.netsight.modules.system.mapper.SysUserMapper;
import com.netsight.modules.system.mapper.SysUserRoleMapper;
import com.netsight.modules.workorder.entity.Repairer;
import com.netsight.modules.workorder.service.RepairerAccountResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private SysUserMapper sysUserMapper;
    @Mock private SysUserRoleMapper sysUserRoleMapper;
    @Mock private SysRolePermissionMapper sysRolePermissionMapper;
    @Mock private JwtUtils jwtUtils;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private NotifyChannelService notifyChannelService;
    @Mock private ChannelRegistry channelRegistry;
    @Mock private RepairerAccountResolver repairerAccountResolver;

    private AuthService service;

    @BeforeEach
    void setUp() {
        service = new AuthService(sysUserMapper, sysUserRoleMapper, sysRolePermissionMapper,
                jwtUtils, redisTemplate, notifyChannelService, channelRegistry,
                repairerAccountResolver);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn("123456");
    }

    private SysUser user(Long id, Long tenantId, String phone, Integer status) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setTenantId(tenantId);
        user.setUsername("user" + id);
        user.setRealName("维修员" + id);
        user.setPhone(phone);
        user.setStatus(status);
        return user;
    }

    private Repairer repairer(Long id, Long tenantId, Long userId, String phone, Integer status) {
        Repairer repairer = new Repairer();
        repairer.setId(id);
        repairer.setTenantId(tenantId);
        repairer.setUserId(userId);
        repairer.setName("维修员");
        repairer.setPhone(phone);
        repairer.setStatus(status);
        return repairer;
    }

    @Test
    void h5LoginReturnsRepairerId() {
        SysUser user = user(22L, 14L, "19900000002", 1);
        Repairer repairer = repairer(101L, 14L, 22L, "19900000002", 1);
        when(sysUserMapper.selectList(any())).thenReturn(List.of(user));
        when(repairerAccountResolver.listBound(14L, 22L)).thenReturn(List.of(repairer));
        when(sysUserRoleMapper.selectRoleKeysByUserId(22L)).thenReturn(List.of("repairer"));
        when(sysUserRoleMapper.selectRoleIdsByUserId(22L)).thenReturn(List.of(4L));
        when(sysRolePermissionMapper.selectPermKeysByRoleId(4L))
                .thenReturn(List.of("workorder:repairer:list"));
        when(jwtUtils.createToken(any())).thenReturn("token");

        Map<String, Object> result = service.loginByPhone("19900000002", "123456", "m");

        assertEquals("token", result.get("token"));
        assertEquals(101L, ((LoginUser) result.get("user")).getRepairerId());
    }

    @Test
    void h5LoginRejectsUserWithoutRepairerArchive() {
        SysUser user = user(30L, 14L, "19900000003", 1);
        when(sysUserMapper.selectList(any())).thenReturn(List.of(user));
        when(repairerAccountResolver.listBound(14L, 30L)).thenReturn(List.of());
        when(repairerAccountResolver.listLegacyByPhone(14L, "19900000003")).thenReturn(List.of());

        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.loginByPhone("19900000003", "123456", "m"));

        assertEquals("当前账号未关联维修人员档案，请联系管理员", exception.getMessage());
    }

    @Test
    void h5LoginRejectsDisabledAccount() {
        SysUser user = user(22L, 14L, "19900000002", 0);
        Repairer repairer = repairer(101L, 14L, 22L, "19900000002", 1);
        when(sysUserMapper.selectList(any())).thenReturn(List.of(user));
        when(repairerAccountResolver.listBound(14L, 22L)).thenReturn(List.of(repairer));

        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.loginByPhone("19900000002", "123456", "m"));

        assertEquals("当前账号已被禁用，请联系管理员", exception.getMessage());
    }

    @Test
    void h5LoginRejectsMultipleRepairerArchives() {
        SysUser user = user(22L, 14L, "19900000002", 1);
        when(sysUserMapper.selectList(any())).thenReturn(List.of(user));
        when(repairerAccountResolver.listBound(14L, 22L)).thenReturn(List.of(
                repairer(101L, 14L, 22L, "19900000002", 1),
                repairer(102L, 14L, 22L, "19900000002", 1)));

        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.loginByPhone("19900000002", "123456", "m"));

        assertEquals("手机号关联多个维修人员账号，请联系管理员处理", exception.getMessage());
    }

    @Test
    void pcLoginStillRejectsMultipleEnabledAccounts() {
        SysUser first = user(22L, 14L, "19900000002", 1);
        SysUser second = user(23L, 14L, "19900000002", 1);
        when(sysUserMapper.selectList(any())).thenReturn(List.of(first, second));

        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.loginByPhone("19900000002", "123456", "pc"));

        assertEquals("手机号关联多个账号，请联系管理员", exception.getMessage());
    }
}
```

```java
package com.netsight.framework.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JwtUtilsTest {

    private JwtUtils jwtUtils;

    @BeforeEach
    void setUp() {
        jwtUtils = new JwtUtils();
        ReflectionTestUtils.setField(jwtUtils, "secret",
                "0123456789abcdef0123456789abcdef0123456789abcdef");
        ReflectionTestUtils.setField(jwtUtils, "expireMinutes", 120L);
        jwtUtils.init();
    }

    @Test
    void tokenCarriesRepairerId() {
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(22L);
        loginUser.setTenantId(14L);
        loginUser.setRepairerId(101L);
        loginUser.setRoles(java.util.Set.of("repairer"));

        String token = jwtUtils.createToken(loginUser);
        Object value = jwtUtils.parseToken(token).get("repairerId");

        assertEquals(101L, ((Number) value).longValue());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```powershell
$mvn = "D:\DevTools\apache-maven-3.9.6\bin\mvn.cmd"
& $mvn -f "D:\gitee\gitee\NetSight1.0\netsight-server\pom.xml" -Dtest=AuthServiceTest,JwtUtilsTest test
```

Expected: 编译失败，提示 `LoginUser.setRepairerId` 不存在或 `AuthService` 构造器参数不匹配。

- [ ] **Step 3: 写最小实现**

`LoginUser.java` 在 `tenantId` 后增加：

```java
    /** 绑定的维修档案 ID（H5 登录写入，PC 登录为空） */
    private Long repairerId;
```

`JwtUtils.java` 的 `createToken` 改为：

```java
    public String createToken(LoginUser loginUser) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expireMinutes * 60 * 1000);
        io.jsonwebtoken.JwtBuilder builder = Jwts.builder()
                .subject(String.valueOf(loginUser.getUserId()))
                .claim("username", loginUser.getUsername())
                .claim("realName", loginUser.getRealName())
                .claim("phone", loginUser.getPhone())
                .claim("tenantId", loginUser.getTenantId())
                .claim("clientType", loginUser.getClientType())
                .claim("roles", loginUser.getRoles() == null ? new java.util.HashSet<>() : loginUser.getRoles())
                .claim("permissions", loginUser.getPermissions() == null ? new java.util.HashSet<>() : loginUser.getPermissions())
                .issuedAt(now)
                .expiration(expiry);
        if (loginUser.getRepairerId() != null) {
            builder.claim("repairerId", loginUser.getRepairerId());
        }
        return builder.signWith(key).compact();
    }
```

`JwtAuthenticationFilter.java` 的 `buildLoginUser` 在 `setTenantId` 后增加：

```java
        Object repairerId = claims.get("repairerId");
        if (repairerId instanceof Number number) {
            loginUser.setRepairerId(number.longValue());
        }
```

`AuthService.java` 顶部把 `RepairerMapper` 替换为 `RepairerAccountResolver`：

```java
import com.netsight.modules.workorder.service.RepairerAccountResolver;
...
    private final RepairerAccountResolver repairerAccountResolver;
```

`loginByPhone` 完整替换为：

```java
    public Map<String, Object> loginByPhone(String phone, String code, String clientType) {
        String redisKey = SMS_CODE_KEY + phone;
        String savedCode = redisTemplate.opsForValue().get(redisKey);
        if (savedCode == null || !savedCode.equals(code)) {
            throw new ServiceException(ResultCode.VERIFY_CODE_ERROR);
        }
        redisTemplate.delete(redisKey);

        List<SysUser> users = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getPhone, phone)
                .orderByAsc(SysUser::getId));
        if (users.isEmpty()) {
            throw new ServiceException(ResultCode.PASSWORD_ERROR);
        }

        SysUser user;
        Repairer repairer = null;
        if ("m".equals(clientType)) {
            List<LoginCandidate> candidates = new java.util.ArrayList<>();
            for (SysUser candidateUser : users) {
                List<Repairer> bound = repairerAccountResolver.listBound(
                        candidateUser.getTenantId(), candidateUser.getId());
                List<Repairer> legacy = bound.isEmpty()
                        ? repairerAccountResolver.listLegacyByPhone(
                                candidateUser.getTenantId(), candidateUser.getPhone())
                        : List.of();
                for (Repairer candidateRepairer : bound) {
                    candidates.add(new LoginCandidate(candidateUser, candidateRepairer));
                }
                for (Repairer candidateRepairer : legacy) {
                    candidates.add(new LoginCandidate(candidateUser, candidateRepairer));
                }
            }
            if (candidates.isEmpty()) {
                throw new ServiceException(500, "当前账号未关联维修人员档案，请联系管理员");
            }
            if (candidates.size() > 1) {
                throw new ServiceException(500, "手机号关联多个维修人员账号，请联系管理员处理");
            }
            user = candidates.get(0).user();
            repairer = candidates.get(0).repairer();
            if (user.getStatus() == null || user.getStatus() != 1) {
                throw new ServiceException(500, "当前账号已被禁用，请联系管理员");
            }
            repairerAccountResolver.assertUsable(repairer);
        } else {
            List<SysUser> enabled = users.stream()
                    .filter(item -> item.getStatus() != null && item.getStatus() == 1)
                    .toList();
            if (enabled.isEmpty()) {
                throw new ServiceException(ResultCode.USER_DISABLED);
            }
            if (enabled.size() > 1) {
                throw new ServiceException(500, "手机号关联多个账号，请联系管理员");
            }
            user = enabled.get(0);
        }

        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(user.getId());
        loginUser.setUsername(user.getUsername());
        loginUser.setRealName(user.getRealName());
        loginUser.setPhone(user.getPhone());
        loginUser.setTenantId(user.getTenantId());
        loginUser.setRepairerId(repairer == null ? null : repairer.getId());
        Set<String> roles = loadRoles(user.getId());
        loginUser.setRoles(roles);
        loginUser.setPermissions(loadPermissions(user.getId(), roles));
        loginUser.setIsSuperAdmin(roles.contains("super_admin"));
        loginUser.setClientType(clientType);

        String token = jwtUtils.createToken(loginUser);
        Map<String, Object> result = new HashMap<>();
        result.put("token", token);
        result.put("user", loginUser);
        return result;
    }

    private record LoginCandidate(SysUser user, Repairer repairer) {
    }
```

- [ ] **Step 4: 运行测试确认通过**

```powershell
$mvn = "D:\DevTools\apache-maven-3.9.6\bin\mvn.cmd"
& $mvn -f "D:\gitee\gitee\NetSight1.0\netsight-server\pom.xml" -Dtest=AuthServiceTest,JwtUtilsTest test
```

Expected: `AuthServiceTest` 5 项、`JwtUtilsTest` 1 项全部通过。

- [ ] **Step 5: 提交**

```powershell
git -C "D:\gitee\gitee\NetSight1.0" add -- "netsight-server/src/main/java/com/netsight/framework/security/LoginUser.java" "netsight-server/src/main/java/com/netsight/framework/security/JwtUtils.java" "netsight-server/src/main/java/com/netsight/framework/security/JwtAuthenticationFilter.java" "netsight-server/src/main/java/com/netsight/modules/system/service/AuthService.java" "netsight-server/src/test/java/com/netsight/modules/system/service/AuthServiceTest.java" "netsight-server/src/test/java/com/netsight/framework/security/JwtUtilsTest.java"
git -C "D:\gitee\gitee\NetSight1.0" commit -m "feat: H5 登录按维修档案绑定关系识别身份"
```

---

### Task 5: H5 当前维修员与派单校验

**Files:**
- Modify: `netsight-server/src/main/java/com/netsight/modules/workorder/controller/MobileWorkOrderController.java`
- Modify: `netsight-server/src/main/java/com/netsight/modules/workorder/service/WorkOrderService.java`
- Test: `netsight-server/src/test/java/com/netsight/modules/workorder/service/WorkOrderServiceTest.java`
- Test: `netsight-server/src/test/java/com/netsight/modules/workorder/controller/MobileWorkOrderControllerTest.java`

**Interfaces:**
- Consumes:
  - `RepairerAccountResolver.resolve(LoginUser): Repairer`
  - `SysUserMapper.selectById(Long): SysUser`
- Produces:
  - `MobileWorkOrderController.currentRepairer(): Repairer`（包级可见，供测试）
  - `WorkOrderService.validateRepairerAccount(Repairer): void`（包级可见，供测试）

- [ ] **Step 1: 写失败测试**

```java
package com.netsight.modules.workorder.service;

import com.netsight.common.exception.ServiceException;
import com.netsight.modules.alert.mapper.NotificationContactMapper;
import com.netsight.modules.alert.mapper.NotificationRuleMapper;
import com.netsight.modules.system.entity.SysUser;
import com.netsight.modules.system.mapper.SysUserMapper;
import com.netsight.modules.workflow.mapper.ApprovalBillMapper;
import com.netsight.modules.workflow.service.WorkflowService;
import com.netsight.modules.workorder.entity.Repairer;
import com.netsight.modules.workorder.mapper.RepairerMapper;
import com.netsight.modules.workorder.mapper.WorkOrderMapper;
import com.netsight.modules.workorder.mapper.WorkOrderPartMapper;
import com.netsight.modules.workorder.mapper.WorkOrderRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkOrderServiceTest {

    @Mock private WorkOrderMapper orderMapper;
    @Mock private WorkOrderRecordMapper recordMapper;
    @Mock private WorkOrderPartMapper orderPartMapper;
    @Mock private RepairerMapper repairerMapper;
    @Mock private NotificationRuleMapper ruleMapper;
    @Mock private NotificationContactMapper contactMapper;
    @Mock private WorkOrderNotifier notifier;
    @Mock private WorkflowService workflowService;
    @Mock private ApprovalBillMapper approvalBillMapper;
    @Mock private SysUserMapper sysUserMapper;

    private WorkOrderService service;

    @BeforeEach
    void setUp() {
        service = new WorkOrderService(orderMapper, recordMapper, orderPartMapper,
                repairerMapper, ruleMapper, contactMapper, notifier, workflowService,
                approvalBillMapper, sysUserMapper);
    }

    private Repairer repairer(Integer type, Long userId) {
        Repairer repairer = new Repairer();
        repairer.setId(101L);
        repairer.setRepairerType(type);
        repairer.setUserId(userId);
        repairer.setStatus(1);
        return repairer;
    }

    private SysUser user(Integer status) {
        SysUser user = new SysUser();
        user.setId(22L);
        user.setStatus(status);
        return user;
    }

    @Test
    void boundEnabledAccountPassesValidation() {
        when(sysUserMapper.selectById(22L)).thenReturn(user(1));

        assertDoesNotThrow(() -> service.validateRepairerAccount(repairer(1, 22L)));
    }

    @Test
    void disabledAccountIsRejected() {
        when(sysUserMapper.selectById(22L)).thenReturn(user(0));

        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.validateRepairerAccount(repairer(1, 22L)));

        assertEquals("该维修人员账号已被禁用，无法接单", exception.getMessage());
    }

    @Test
    void externalRepairerWithoutAccountIsRejected() {
        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.validateRepairerAccount(repairer(2, null)));

        assertEquals("该外聘维修人员未绑定登录账号，无法 H5 接单，请先补建账号", exception.getMessage());
    }

    @Test
    void legacyUnboundRepairerStillCanBeDispatched() {
        assertDoesNotThrow(() -> service.validateRepairerAccount(repairer(0, null)));
    }
}
```

```java
package com.netsight.modules.workorder.controller;

import com.netsight.framework.security.LoginUser;
import com.netsight.modules.spare.mapper.SparePartMapper;
import com.netsight.modules.workorder.entity.Repairer;
import com.netsight.modules.workorder.mapper.WorkOrderMapper;
import com.netsight.modules.workorder.mapper.WorkOrderPartMapper;
import com.netsight.modules.workorder.mapper.WorkOrderRecordMapper;
import com.netsight.modules.workorder.service.RepairerAccountResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MobileWorkOrderControllerTest {

    @Mock private WorkOrderMapper orderMapper;
    @Mock private WorkOrderRecordMapper recordMapper;
    @Mock private WorkOrderPartMapper orderPartMapper;
    @Mock private RepairerAccountResolver repairerAccountResolver;
    @Mock private SparePartMapper sparePartMapper;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void currentRepairerDelegatesToResolver() {
        MobileWorkOrderController controller = new MobileWorkOrderController(
                orderMapper, recordMapper, orderPartMapper, repairerAccountResolver,
                sparePartMapper);
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(22L);
        loginUser.setTenantId(14L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));
        Repairer repairer = new Repairer();
        repairer.setId(101L);
        when(repairerAccountResolver.resolve(any())).thenReturn(repairer);

        assertSame(repairer, controller.currentRepairer());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```powershell
$mvn = "D:\DevTools\apache-maven-3.9.6\bin\mvn.cmd"
& $mvn -f "D:\gitee\gitee\NetSight1.0\netsight-server\pom.xml" -Dtest=WorkOrderServiceTest,MobileWorkOrderControllerTest test
```

Expected: 编译失败，提示构造器参数不匹配或 `validateRepairerAccount` 不存在。

- [ ] **Step 3: 写最小实现**

`WorkOrderService.java` 增加 import：

```java
import com.netsight.modules.system.entity.SysUser;
import com.netsight.modules.system.mapper.SysUserMapper;
```

字段在 `approvalBillMapper` 后增加：

```java
    private final SysUserMapper sysUserMapper;
```

在 `dispatch` 完成“维修员存在、在岗、同租户”校验后加入：

```java
        validateRepairerAccount(repairer);
```

增加方法：

```java
    /**
     * 派单前校验维修员是否拥有可登录账号。
     * 存量 type=0 且未绑定账号的档案保留可派，避免历史工单无法流转。
     */
    void validateRepairerAccount(Repairer repairer) {
        if (repairer.getUserId() != null) {
            SysUser user = sysUserMapper.selectById(repairer.getUserId());
            if (user == null) {
                throw new ServiceException(500, "维修人员账号不存在，请重新选择");
            }
            if (user.getStatus() == null || user.getStatus() != 1) {
                throw new ServiceException(500, "该维修人员账号已被禁用，无法接单");
            }
        }
        if (repairer.getRepairerType() != null
                && repairer.getRepairerType() == 2
                && repairer.getUserId() == null) {
            throw new ServiceException(500,
                    "该外聘维修人员未绑定登录账号，无法 H5 接单，请先补建账号");
        }
    }
```

`MobileWorkOrderController.java` 顶部删除 `RepairerMapper` 字段与 import，改为：

```java
import com.netsight.modules.workorder.service.RepairerAccountResolver;
...
    private final RepairerAccountResolver repairerAccountResolver;
```

`currentRepairer()` 改为：

```java
    Repairer currentRepairer() {
        return repairerAccountResolver.resolve(SecurityUtils.getLoginUser());
    }
```

- [ ] **Step 4: 运行测试确认通过**

```powershell
$mvn = "D:\DevTools\apache-maven-3.9.6\bin\mvn.cmd"
& $mvn -f "D:\gitee\gitee\NetSight1.0\netsight-server\pom.xml" -Dtest=WorkOrderServiceTest,MobileWorkOrderControllerTest test
```

Expected: `WorkOrderServiceTest` 4 项、`MobileWorkOrderControllerTest` 1 项全部通过。

- [ ] **Step 5: 提交**

```powershell
git -C "D:\gitee\gitee\NetSight1.0" add -- "netsight-server/src/main/java/com/netsight/modules/workorder/controller/MobileWorkOrderController.java" "netsight-server/src/main/java/com/netsight/modules/workorder/service/WorkOrderService.java" "netsight-server/src/test/java/com/netsight/modules/workorder/service/WorkOrderServiceTest.java" "netsight-server/src/test/java/com/netsight/modules/workorder/controller/MobileWorkOrderControllerTest.java"
git -C "D:\gitee\gitee\NetSight1.0" commit -m "feat: 派单校验维修人员账号并统一 H5 当前维修员解析"
```

---

### Task 6: 前端选人、类型与补建入口

**Files:**
- Modify: `netsight-ui/src/api/workorder/repairer.js`
- Create: `netsight-ui/src/views/workorder/repairer/components/UserPickerDialog.vue`
- Modify: `netsight-ui/src/views/workorder/repairer/index.vue`

**Interfaces:**
- Consumes:
  - `GET /workorder/repairer/list?keyword=&status=`
  - `GET /workorder/repairer/user-options?keyword=&pageNum=&pageSize=`
  - `POST /workorder/repairer`
  - `POST /workorder/repairer/{id}/account`
  - `PUT /workorder/repairer`
  - `DELETE /workorder/repairer/{id}`
- Produces:
  - `listRepairerUserOptions(query)`、`bindRepairerAccount(id, data)`
  - `UserPickerDialog` 组件事件 `select(user)` 与 `update:visible`

- [ ] **Step 1: 补 API**

`netsight-ui/src/api/workorder/repairer.js` 末尾追加：

```javascript
// 维修人员新增页的选人候选
export function listRepairerUserOptions(query) {
  return request({
    url: '/workorder/repairer/user-options',
    method: 'get',
    params: query
  }).then(res => res.data)
}

// 给存量维修人员补建账号
export function bindRepairerAccount(id, data) {
  return request({
    url: '/workorder/repairer/' + id + '/account',
    method: 'post',
    data: data
  }).then(res => res.data)
}
```

- [ ] **Step 2: 新建选人弹窗**

`UserPickerDialog.vue`：

```vue
<template>
  <el-dialog title="选择用户" :visible="visible" width="720px" append-to-body
             :close-on-click-modal="false" @close="close">
    <el-form :inline="true" class="picker-search">
      <el-form-item label="关键字">
        <el-input v-model="query.keyword" placeholder="姓名/手机号/登录账号" clearable
                  size="small" style="width: 220px" @keyup.enter.native="handleQuery" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">搜索</el-button>
      </el-form-item>
    </el-form>

    <el-table ref="userTable" border v-loading="loading" :data="userList" highlight-current-row
              @row-click="handleRowClick" style="width: 100%">
      <el-table-column label="姓名" prop="realName" width="180" align="center" />
      <el-table-column label="手机号" prop="phone" width="180" align="center" />
      <el-table-column label="登录账号" prop="username" min-width="180" align="center" />
    </el-table>
    <div v-if="!loading && userList.length === 0" class="empty-tip">暂无可绑定的用户</div>

    <pagination v-show="total > 0" :total="total" :page.sync="query.pageNum"
                :limit.sync="query.pageSize" @pagination="getList" />

    <div slot="footer" class="dialog-footer">
      <el-button type="primary" :disabled="!selected" @click="confirm">确 定</el-button>
      <el-button @click="close">取 消</el-button>
    </div>
  </el-dialog>
</template>

<script>
import { listRepairerUserOptions } from '@/api/workorder/repairer'
import Pagination from '@/components/Pagination'

export default {
  name: 'UserPickerDialog',
  components: { Pagination },
  props: {
    visible: { type: Boolean, default: false }
  },
  data() {
    return {
      loading: false,
      userList: [],
      total: 0,
      selected: null,
      query: { pageNum: 1, pageSize: 10, keyword: undefined }
    }
  },
  watch: {
    visible(value) {
      if (value) {
        this.selected = null
        this.query.pageNum = 1
        this.query.keyword = undefined
        this.getList()
      }
    }
  },
  methods: {
    getList() {
      this.loading = true
      listRepairerUserOptions(this.query).then(response => {
        this.userList = response.rows || []
        this.total = response.total || 0
        this.loading = false
      }).catch(() => { this.loading = false })
    },
    handleQuery() {
      this.query.pageNum = 1
      this.getList()
    },
    handleRowClick(row) {
      this.selected = row
      this.$refs.userTable.setCurrentRow(row)
    },
    confirm() {
      if (!this.selected) return
      this.$emit('select', this.selected)
      this.close()
    },
    close() {
      this.$emit('update:visible', false)
    }
  }
}
</script>

<style scoped>
.picker-search {
  margin-bottom: 8px;
}
.empty-tip {
  padding: 18px 0;
  color: #909399;
  text-align: center;
}
</style>
```

- [ ] **Step 3: 改造维修人员页面**

`index.vue` 完整替换为：

```vue
<template>
  <div class="app-container">
    <el-form :model="queryParams" ref="queryForm" :inline="true" v-show="showSearch" class="search-form">
      <el-form-item label="姓名/手机号" prop="keyword">
        <el-input v-model="queryParams.keyword" placeholder="姓名/手机号" clearable size="small"
                  style="width: 160px" @keyup.enter.native="handleQuery" />
      </el-form-item>
      <el-form-item label="状态" prop="status">
        <el-select v-model="queryParams.status" placeholder="在岗状态" clearable size="small" style="width: 120px">
          <el-option label="在岗" :value="1" />
          <el-option label="休假" :value="0" />
          <el-option label="离职" :value="2" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">搜索</el-button>
        <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5">
        <el-button v-hasPermi="['workorder:repairer:add']" type="primary" plain icon="el-icon-plus"
                   size="mini" @click="handleAdd">新增维修人员</el-button>
      </el-col>
      <right-toolbar :showSearch.sync="showSearch" @queryTable="getList"></right-toolbar>
    </el-row>

    <el-table border v-loading="loading" :data="repairerList">
      <el-table-column label="姓名" prop="name" width="220" align="center">
        <template slot-scope="scope">
          <div>{{ scope.row.name }}</div>
          <el-tag size="mini" effect="plain" :type="typeInfo(scope.row.repairerType).tag">
            {{ typeInfo(scope.row.repairerType).text }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="手机号" prop="phone" width="195" align="center">
        <template slot-scope="scope">
          <div>{{ scope.row.phone || '-' }}</div>
          <div class="sub-line">
            <span v-if="scope.row.loginUsername">{{ scope.row.loginUsername }}</span>
            <span v-else class="muted">未绑定账号</span>
            <span v-if="scope.row.userStatus === 0" class="danger-text">已禁用</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="负责区域" prop="region" min-width="60" show-overflow-tooltip align="center" />
      <el-table-column label="设备类型" min-width="105" align="center">
        <template slot-scope="scope">
          <el-tag v-for="t in parseArr(scope.row.deviceTypes)" :key="t" size="mini"
                  style="margin-right:4px">{{ deviceTypeText(t) }}</el-tag>
          <span v-if="!scope.row.deviceTypes">-</span>
        </template>
      </el-table-column>
      <el-table-column label="技能" prop="skills" min-width="135" show-overflow-tooltip align="center" />
      <el-table-column label="在岗状态" width="90" align="center">
        <template slot-scope="scope">
          <el-tag v-if="scope.row.status === 2" size="mini" type="danger">离职</el-tag>
          <el-switch v-else :value="scope.row.status === 1" @change="handleStatusChange(scope.row)" />
        </template>
      </el-table-column>
      <el-table-column label="创建时间" prop="createTime" width="145" align="center" />
      <el-table-column label="操作" align="center" width="220" class-name="small-padding fixed-width">
        <template slot-scope="scope">
          <el-button v-hasPermi="['workorder:repairer:edit']" size="mini" type="text"
                     icon="el-icon-edit" @click="handleUpdate(scope.row)">修改</el-button>
          <el-button v-if="!scope.row.userId" v-hasPermi="['workorder:repairer:edit']" size="mini"
                     type="text" icon="el-icon-user" @click="handleBindAccount(scope.row)">补建账号</el-button>
          <el-button v-hasPermi="['workorder:repairer:remove']" size="mini" type="text"
                     icon="el-icon-delete" style="color:#f56c6c" @click="handleDelete(scope.row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <pagination v-show="total > 0" :total="total" :page.sync="queryParams.pageNum"
                :limit.sync="queryParams.pageSize" @pagination="getList" />

    <el-dialog :title="form.id ? '修改维修人员' : '新增维修人员'" :visible.sync="open" width="620px"
               append-to-body :close-on-click-modal="false">
      <el-form ref="form" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="人员类型">
          <el-tag v-if="form.id && form.repairerType === 0" type="info">未分类</el-tag>
          <el-radio-group v-else v-model="form.repairerType" :disabled="!!form.id"
                          @change="handleTypeChange">
            <el-radio :label="1">内聘（园区内部）</el-radio>
            <el-radio :label="2">外聘（外包）</el-radio>
          </el-radio-group>
          <div v-if="form.id && form.repairerType === 0" class="form-tip">
            请通过列表中的“补建账号”完成人员分类
          </div>
        </el-form-item>

        <el-form-item v-if="form.repairerType === 1" label="选择用户">
          <el-button size="small" type="primary" plain icon="el-icon-user"
                     :disabled="!!form.id" @click="openUserPicker">选择用户</el-button>
          <div v-if="form.userId" class="selected-user">
            {{ form.name }} / {{ form.phone }} / {{ form.username }}
          </div>
          <div v-else class="form-tip">从用户管理中选择本租户已启用且有手机号的用户</div>
        </el-form-item>

        <el-row>
          <el-col :span="12">
            <el-form-item label="姓名" prop="name">
              <el-input v-model="form.name" placeholder="姓名" :disabled="form.repairerType !== 2" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="手机号" prop="phone">
              <el-input v-model="form.phone" placeholder="手机号" :disabled="form.repairerType !== 2" />
            </el-form-item>
          </el-col>
        </el-row>
        <div v-if="form.repairerType === 2 && !form.id" class="form-tip phone-tip">
          保存后自动创建登录账号，H5 用手机号 + 短信验证码登录
        </div>

        <el-form-item label="负责区域">
          <el-input v-model="form.region" placeholder="如：1号楼/2号楼、3号楼" />
        </el-form-item>
        <el-form-item label="设备类型">
          <el-select v-model="form.deviceTypes" multiple placeholder="可维修设备类型" style="width:100%">
            <el-option label="网络设备" value="network" />
            <el-option label="摄像头" value="camera" />
            <el-option label="NVR" value="nvr" />
            <el-option label="门禁控制器" value="door_controller" />
          </el-select>
        </el-form-item>
        <el-form-item label="技能标签">
          <el-input v-model="form.skills" placeholder="如：交换机维修,光纤熔接" />
        </el-form-item>
        <el-form-item label="在岗状态">
          <el-radio-group v-model="form.status">
            <el-radio :label="1">在岗</el-radio>
            <el-radio :label="0">休假</el-radio>
            <el-radio :label="2">离职</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitForm">确 定</el-button>
        <el-button @click="cancel">取 消</el-button>
      </div>
    </el-dialog>

    <el-dialog title="补建账号" :visible.sync="bindOpen" width="420px" append-to-body>
      <el-radio-group v-model="bindType">
        <el-radio :label="1">内聘（绑定同手机号用户）</el-radio>
        <el-radio :label="2">外聘（创建新账号）</el-radio>
      </el-radio-group>
      <div class="form-tip">当前维修人员：{{ bindRow.name }} / {{ bindRow.phone }}</div>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitBind">确 定</el-button>
        <el-button @click="bindOpen = false">取 消</el-button>
      </div>
    </el-dialog>

    <user-picker-dialog :visible.sync="userPickerVisible" @select="onUserSelected" />
  </div>
</template>

<script>
import { listRepairer, addRepairer, updateRepairer, delRepairer, bindRepairerAccount } from '@/api/workorder/repairer'
import Pagination from '@/components/Pagination'
import UserPickerDialog from './components/UserPickerDialog'

export default {
  name: 'Repairer',
  components: { Pagination, UserPickerDialog },
  data() {
    return {
      loading: false,
      showSearch: true,
      repairerList: [],
      total: 0,
      queryParams: { pageNum: 1, pageSize: 10, keyword: undefined, status: undefined },
      open: false,
      userPickerVisible: false,
      bindOpen: false,
      bindRow: {},
      bindType: 1,
      form: {},
      rules: {}
    }
  },
  created() {
    this.getList()
  },
  methods: {
    getList() {
      this.loading = true
      listRepairer(this.queryParams).then(response => {
        this.repairerList = response.rows || []
        this.total = response.total || 0
        this.loading = false
      }).catch(() => { this.loading = false })
    },
    handleQuery() {
      this.queryParams.pageNum = 1
      this.getList()
    },
    resetQuery() {
      this.resetForm('queryForm')
      this.handleQuery()
    },
    handleAdd() {
      this.reset()
      this.form = { repairerType: 1, status: 1, deviceTypes: [], userId: null }
      this.open = true
    },
    handleUpdate(row) {
      this.reset()
      this.form = { ...row, deviceTypes: this.parseArr(row.deviceTypes), username: row.loginUsername }
      this.open = true
    },
    handleTypeChange() {
      if (this.form.id) return
      this.form.userId = null
      this.form.name = ''
      this.form.phone = ''
      this.form.username = ''
    },
    openUserPicker() {
      if (this.form.id) return
      this.userPickerVisible = true
    },
    onUserSelected(user) {
      this.form.userId = user.id
      this.form.name = user.realName
      this.form.phone = user.phone
      this.form.username = user.username
    },
    submitForm() {
      if (this.form.repairerType === 1 && !this.form.id && !this.form.userId) {
        this.$modal.msgWarning('请选择用户')
        return
      }
      if (this.form.repairerType === 2) {
        if (!this.form.name) {
          this.$modal.msgWarning('姓名不能为空')
          return
        }
        if (!/^1[3-9]\d{9}$/.test(this.form.phone || '')) {
          this.$modal.msgWarning('手机号格式不正确')
          return
        }
      }
      this.$refs['form'].validate(valid => {
        if (!valid) return
        const payload = {
          id: this.form.id,
          repairerType: this.form.repairerType,
          userId: this.form.userId,
          name: this.form.name,
          phone: this.form.phone,
          region: this.form.region,
          deviceTypes: JSON.stringify(this.form.deviceTypes || []),
          skills: this.form.skills,
          status: this.form.status,
          remark: this.form.remark
        }
        if (this.form.id) {
          updateRepairer(payload).then(() => {
            this.$modal.msgSuccess('修改成功')
            this.open = false
            this.getList()
          })
          return
        }
        addRepairer(payload).then(result => {
          if (result && result.accountCreated) {
            this.$modal.msgSuccess('维修人员已添加，登录账号：' + result.loginUsername)
          } else {
            this.$modal.msgSuccess('维修人员已添加')
          }
          this.open = false
          this.getList()
        })
      })
    },
    handleStatusChange(row) {
      if (row.status === 2) return
      const target = row.status === 1 ? 0 : 1
      updateRepairer({
        id: row.id,
        status: target,
        deviceTypes: JSON.stringify(this.parseArr(row.deviceTypes))
      }).then(() => {
        this.$modal.msgSuccess(target === 1 ? '已设为在岗' : '已设为休假')
        this.getList()
      })
    },
    handleBindAccount(row) {
      this.bindRow = row
      this.bindType = row.repairerType === 2 ? 2 : 1
      this.bindOpen = true
    },
    submitBind() {
      bindRepairerAccount(this.bindRow.id, { repairerType: this.bindType }).then(result => {
        this.$modal.msgSuccess(result && result.accountCreated ? '账号已创建并绑定' : '账号已绑定')
        this.bindOpen = false
        this.getList()
      })
    },
    handleDelete(row) {
      const message = row.repairerType === 2
        ? '确认删除维修人员 ' + row.name + '？其登录账号将同时被禁用。'
        : '确认删除维修人员 ' + row.name + '？用户在用户管理中的账号将保留。'
      this.$confirm(message, '警告', { type: 'warning' }).then(() => {
        delRepairer(row.id).then(() => {
          this.$modal.msgSuccess('删除成功')
          this.getList()
        })
      })
    },
    typeInfo(type) {
      if (type === 1) return { text: '内聘', tag: 'success' }
      if (type === 2) return { text: '外聘', tag: 'warning' }
      return { text: '未分类', tag: 'info' }
    },
    parseArr(value) {
      if (!value) return []
      try { return JSON.parse(value) } catch (e) { return [] }
    },
    deviceTypeText(type) {
      return { network: '网络设备', camera: '摄像头', nvr: 'NVR', door_controller: '门禁控制器' }[type] || type
    },
    cancel() {
      this.open = false
      this.reset()
    },
    reset() {
      this.form = {}
    }
  }
}
</script>

<style scoped>
.sub-line {
  margin-top: 2px;
  font-size: 12px;
  color: #606266;
}
.muted {
  color: #909399;
}
.danger-text {
  margin-left: 4px;
  color: #f56c6c;
}
.form-tip {
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
}
.phone-tip {
  margin: -8px 0 12px 90px;
}
.selected-user {
  margin-top: 6px;
  font-size: 13px;
  color: #409eff;
}
</style>
```

- [ ] **Step 4: 构建确认**

```powershell
$env:Path = "D:\DevTools\node16;" + $env:Path
Set-Location "D:\gitee\gitee\NetSight1.0\netsight-ui"
npm run build:prod
```

Expected: 构建成功，`dist` 生成；控制台无 `UserPickerDialog` 或 `index.vue` 模板编译错误。

- [ ] **Step 5: 提交**

```powershell
git -C "D:\gitee\gitee\NetSight1.0" add -- "netsight-ui/src/api/workorder/repairer.js" "netsight-ui/src/views/workorder/repairer/components/UserPickerDialog.vue" "netsight-ui/src/views/workorder/repairer/index.vue"
git -C "D:\gitee\gitee\NetSight1.0" commit -m "feat: 维修人员页面支持选人绑定与补建账号"
```

---

### Task 7: 测试库升级、构建部署与 20 项验收

**Files:**
- Use: `netsight-server/sql/netsight-upgrade-v1.3.1-repairer-user-binding.sql`
- Verify: `netsight-server/target/netsight-server.jar`
- Verify: `netsight-ui/dist`

**Interfaces:**
- Consumes: Task 1–6 的代码与构建产物
- Produces: 测试环境 `115.159.194.149` 的可验收版本；20 项测试矩阵结果；回滚记录

- [ ] **Step 1: 执行升级 SQL 前备份**

```powershell
ssh root@115.159.194.149 "mysqldump -usadmin -p netsight repairer > /opt/nams-server/backup-repairer-20261108.sql"
ssh root@115.159.194.149 "mysql -usadmin -p netsight -e 'SHOW COLUMNS FROM repairer; SHOW INDEX FROM repairer;'"
```

Expected: 备份文件非空；当前 `repairer` 没有 `user_id`、`repairer_type` 列，也没有 `uk_repairer_user`、`idx_repairer_phone`。

- [ ] **Step 2: 上传并执行 SQL**

```powershell
scp "D:\gitee\gitee\NetSight1.0\netsight-server\sql\netsight-upgrade-v1.3.1-repairer-user-binding.sql" root@115.159.194.149:/tmp/netsight-upgrade-v1.3.1-repairer-user-binding.sql
ssh root@115.159.194.149 "mysql -usadmin -p netsight < /tmp/netsight-upgrade-v1.3.1-repairer-user-binding.sql"
ssh root@115.159.194.149 'mysql -usadmin -p netsight -e "DESC repairer; SHOW INDEX FROM repairer;"'
```

Expected: `user_id bigint NULL`、`repairer_type tinyint NOT NULL DEFAULT 0`、两个索引存在。

- [ ] **Step 3: 后端全量测试与打包**

```powershell
$mvn = "D:\DevTools\apache-maven-3.9.6\bin\mvn.cmd"
& $mvn -f "D:\gitee\gitee\NetSight1.0\netsight-server\pom.xml" test
& $mvn -f "D:\gitee\gitee\NetSight1.0\netsight-server\pom.xml" clean package -DskipTests
```

Expected: 全部单测通过；`netsight-server\target\netsight-server.jar` 存在且修改时间为本次构建。

- [ ] **Step 4: 部署后端并重启**

```powershell
scp "D:\gitee\gitee\NetSight1.0\netsight-server\target\netsight-server.jar" root@115.159.194.149:/opt/nams-server/netsight-server.jar
ssh root@115.159.194.149 "ls -l /opt/nams-server/start_nams.sh /opt/nams-server/start.sh"
ssh root@115.159.194.149 "if [ -f /opt/nams-server/start_nams.sh ]; then cd /opt/nams-server && ./start_nams.sh; else cd /opt/nams-server && ./start.sh; fi"
ssh root@115.159.194.149 "sleep 20; tail -n 60 /opt/nams-server/logs/netsight.log"
```

Expected: 启动日志无 `WeakKeyException`、无 `BeanCreationException`；`Started NetsightApplication` 出现。若 `start_nams.sh` 不存在而只有 `start.sh`，执行 `./start.sh` 并在验收记录中写明实际脚本名。

- [ ] **Step 5: 构建并部署前端**

```powershell
$env:Path = "D:\DevTools\node16;" + $env:Path
Set-Location "D:\gitee\gitee\NetSight1.0\netsight-ui"
npm run build:prod
Push-Location "D:\gitee\gitee\NetSight1.0\netsight-ui"
& "C:\Windows\System32\tar.exe" -czf dist.tar.gz dist
Pop-Location
scp "D:\gitee\gitee\NetSight1.0\netsight-ui\dist.tar.gz" root@115.159.194.149:/tmp/netsight-dist.tar.gz
ssh root@115.159.194.149 'rm -rf /opt/nams-ui/dist_new && mkdir -p /opt/nams-ui/dist_new && python3 -c "import tarfile; tarfile.open(\"/tmp/netsight-dist.tar.gz\").extractall(\"/opt/nams-ui/dist_new\")" && rm -rf /opt/nams-ui/dist && mv /opt/nams-ui/dist_new/dist /opt/nams-ui/dist'
```

Expected: `/opt/nams-ui/dist/index.html` 存在；浏览器访问 `http://115.159.194.149/` 能看到维修人员页面新列。

- [ ] **Step 6: 执行 20 项测试矩阵**

按设计文档 §9.1 逐项执行，测试租户 14，短信码 `123456`。至少记录以下关键证据：

```sql
-- 1 内聘绑定：档案应 user_id=22、repairer_type=1
SELECT id, tenant_id, name, phone, user_id, repairer_type
FROM repairer WHERE tenant_id = 14 AND user_id = 22;

-- 6 外聘建号：张三账号角色只有 repairer
SELECT u.id, u.username, u.real_name, u.phone, r.role_key
FROM sys_user u
JOIN sys_user_role ur ON ur.user_id = u.id
JOIN sys_role r ON r.id = ur.role_id
WHERE u.phone = '19900000009';

-- 17 删除外聘：档案逻辑删除、账号 status=0
SELECT rp.id, rp.del_flag, u.id AS user_id, u.status
FROM repairer rp LEFT JOIN sys_user u ON u.id = rp.user_id
WHERE rp.tenant_id = 14 AND rp.name = '张三';

-- 18 修改外聘手机号：双表同步（按档案姓名定位，避免假设自增 id）
SELECT rp.phone AS repairer_phone, u.phone AS user_phone
FROM repairer rp JOIN sys_user u ON u.id = rp.user_id
WHERE rp.tenant_id = 14 AND rp.name = '张三';

-- 19 存量兼容：user_id 为空且同租户手机号唯一命中
SELECT id, tenant_id, name, phone, user_id, repairer_type
FROM repairer WHERE tenant_id = 14 AND user_id IS NULL;
```

逐项对照下表执行并记录结果：

| # | 场景 | 步骤 | 预期 |
|---|------|------|------|
| 1 | 内聘绑定成功 | 维修人员页新增，选用户 22（维修员甲） | 档案 `user_id=22`、`repairer_type=1`，姓名/手机号与账号一致，H5 可登录 |
| 2 | 内聘重复绑定 | 再选用户 22 | 提示“该用户已绑定其他维修人员档案” |
| 3 | 内聘跨租户 | 用租户 1 管理员选租户 14 用户 | 提示“该用户不属于当前租户” |
| 4 | 内聘用户禁用 | 先把用户 22 禁用，再绑定 | 提示“该用户已被禁用，请先在用户管理中启用” |
| 5 | 内聘用户无手机号 | 构造无手机号用户 | 选人列表不显示；接口绑定提示“该用户未维护手机号，无法登录 H5” |
| 6 | 外聘建号成功 | 新增外聘，姓名张三，手机号 19900000009 | 生成 `sys_user`，角色 `repairer`，档案 `repairer_type=2`，H5 可登录 |
| 7 | 外聘手机号重复 | 用已存在用户手机号新增外聘 | 提示“该手机号已被本租户用户占用，请改用内聘选择” |
| 8 | 外聘账号角色 | 查 `sys_user_role` | 只有 `repairer`，无 `super_admin`、`tenant_admin` |
| 9 | H5 内聘登录 | 19900000002 + 123456 | 登录成功，`/m/order/my-list` 只返回自己的工单 |
| 10 | H5 外聘登录 | 19900000009 + 123456 | 登录成功，可接单 |
| 11 | H5 无档案 | 用未绑定用户手机号登录 | 提示“当前账号未关联维修人员档案，请联系管理员” |
| 12 | H5 休假 | 把档案 `status` 改 0 后登录 | 提示“当前维修人员为休假状态，暂不能登录 H5，请联系管理员调整在岗状态” |
| 13 | H5 离职 | 通过修改弹窗把档案状态设为离职后登录 | 提示“当前维修人员已离职，不能登录 H5” |
| 14 | H5 账号禁用 | 把 `sys_user.status` 改 0 后登录 | 提示“当前账号已被禁用，请联系管理员” |
| 15 | H5 同号多档案 | 构造同租户两个用户同手机号且都绑定档案 | 登录拒绝，提示“手机号关联多个维修人员账号，请联系管理员处理” |
| 16 | 派单后归属 | 管理员派单给外聘，外聘 H5 查看 | `my-list` 出现该工单；他人 `detail` 拒绝 |
| 17 | 删除外聘 | 删除外聘档案 | 档案 `del_flag=1`，账号 `status=0`，H5 登录失败 |
| 18 | 修改外聘手机号 | 把外聘手机号改为新号 | `sys_user.phone` 与 `repairer.phone` 同步，新号可登录 |
| 19 | 存量兼容 | 构造 `user_id=NULL`、同租户手机号唯一命中的档案 | H5 可登录；列表显示“未绑定账号” |
| 20 | 租户隔离 | 租户 14 维修员访问租户 1 工单 | 列表不可见，详情拒绝 |

- [ ] **Step 7: 记录回滚与验收结论**

将 Step 6 的 20 项结果写入 `docs/superpowers/plans/2026-11-08-repairer-user-account-binding-acceptance.md`，至少包含：执行时间、测试环境版本、实际重启脚本名、20 项逐项结果、回滚演练结论。然后提交计划与验收记录：

```powershell
git -C "D:\gitee\gitee\NetSight1.0" add -- "docs/superpowers/plans/2026-11-08-repairer-user-account-binding.md" "docs/superpowers/plans/2026-11-08-repairer-user-account-binding-acceptance.md"
git -C "D:\gitee\gitee\NetSight1.0" commit -m "docs: 新增维修人员账号绑定改造实现计划与验收记录"
```

回滚步骤：

```sql
-- 回滚前先导出已绑定关系
SELECT id, tenant_id, user_id, repairer_type FROM repairer;

-- 确认无业务依赖后再删除索引与字段
ALTER TABLE repairer DROP INDEX uk_repairer_user;
ALTER TABLE repairer DROP INDEX idx_repairer_phone;
ALTER TABLE repairer DROP COLUMN repairer_type;
ALTER TABLE repairer DROP COLUMN user_id;
```

Expected: 验收记录包含 20 项结果、执行人、执行时间、测试库版本；回滚 SQL 已确认可在备份库演练。

---

## Self-Review

- **Spec coverage:** 设计文档 §3（DDL）→ Task 1；§4（实体）→ Task 1；§5（用户与维修人员接口）→ Task 2、Task 3；§5.1（H5 登录算法）→ Task 4；§5.2（移动端）→ Task 5；§5.3（派单）→ Task 5；§5.4（联动）→ Task 2、Task 3；§5.5（状态矩阵）→ Task 4、Task 5、Task 7；§6（前端）→ Task 6；§7（权限、安全、审计）→ Task 2、Task 3（显式租户校验、权限串、操作日志）；§8（存量兼容）→ Task 1、Task 4；§9.1/§9.2（测试矩阵与验收 SQL）→ Task 7；§9.3（构建部署）→ Task 7；§9.4（回滚）→ Task 7；§10（实施顺序）→ Task 1–7 的任务顺序；§11（风险与决策）→ Task 7 回滚演练与验收记录；§12/§13 是方案自检与交接说明，不产生代码。
- **Placeholder scan:** 未发现占位标记；每个代码步骤都有完整代码；部署步骤中的密码通过交互输入或 `MYSQL_PWD` 环境变量提供，不把明文密码写入仓库。
- **Type consistency:** `Repairer.userId` 为 `Long`，`repairerType` 为 `Integer`；`RepairerSaveResult` 字段为 `id/loginUsername/accountCreated`；`RepairerAccountRequest` 字段为 `repairerType`；`SysUserOption` 字段为 `id/username/realName/phone`；前后端字段名一致。

