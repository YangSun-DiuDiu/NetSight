# 边缘网关设备清单本地录入与云端同步 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把设备清单权威源下沉到边缘网关：租户在网关本地页面增删改设备，网关按 5 分钟周期把全量清单同步到云端同租户；云端设备管理改为只读，避免云端录入错误导致 ping 目标缺失、设备告警收不到。

**Architecture:** 网关本地 `conf/mapping.json` 是唯一权威源，新增 `revision/updatedAt` 并用原子写保护；`MappingSync` 只读本地文件并生成 `ping.json`，新增 `InventorySync` 负责向云端全量推送。云端新增 `POST /edge/config/devices/sync`，按 `gateway_id + device_code` 幂等 upsert，清单外设备软删除，并把设备管理写接口全部关闭。

**Tech Stack:** Java 21、Spring Boot 4.0.8、Jackson 3（`tools.jackson`）、MyBatis-Plus、MySQL 8、Vue 2 + Element UI、原生 JS 本地管理页。

**Spec:** `docs/superpowers/specs/2026-11-24-edge-device-inventory-sync-design.md`

## 执行状态（2026-11-24 更新）

- **代码与提交**：Task 1–8 已实现；Task 9 的主链路部署与验证已完成。分支 `feature/wechat-mp-full-support` 最新提交为 `5a6dce6`，已推送 GitHub，远端与本地 HEAD 一致。
- **部署产物**：115 已部署 `netsight-server.jar` 与 `netsight-ui/dist`，健康检查 `UP`；`.199` 已部署包含本地清单构造器修复的 `nams-agent.jar`，服务 `nams-agent` 正常运行。
- **运行状态**：`.199` 本地清单 `revision=6`、设备 84 台、`ping.json` 84 条；云端 `edge_gateway` id=14 为 `inventory_revision=6`、`inventory_device_count=84`，84 台设备全部 `source=edge/del_flag=0`。
- **已验证链路**：本地管理员登录；新增设备后 `revision 1→2`、本地与云端均 85 台；编辑后 `revision=3`、云端字段同步更新；删除后 `revision=4`、云端 `del_flag=2`；使用相同设备编码重新新增后恢复原云端记录 `del_flag=0`；最终删除并清理测试记录，恢复 84 台。
- **部署事故与修复**：`LocalInventoryStore` 多构造器导致 Spring 启动失败，已为 `ConfigHolder` 构造器补 `@Autowired` 并增加反射回归断言；网关全量测试通过。
- **尚未闭环的验证**：本地管理页与云端设备页的浏览器视觉验收未截图；空清单拒绝、409 版本冲突重试、同步失败退避仅在单测覆盖；`清空全部` 未进行真实破坏性验证；云端写接口 403 仅通过单测验证。
- **环境与文档待办**：115 后端仍以 `--spring.profiles.active=staging` 启动，未确认是否切 `prod`；`AGENTS.md` 尚未追加本次部署记录；本计划的任务复选框保留未勾选，执行状态以本节为准。
- **访问入口**：本地管理页为 `http://192.168.1.199:8081/`；默认管理员 `admin`，默认密码 `admin123`。

## Global Constraints

- 工作分支：`feature/wechat-mp-full-support`，仓库根：`D:\gitee\worktrees\NetSight1.0-wechat-mp-full-support`。
- JDK：`D:\DevTools\jdk-21`；Maven：`D:\DevTools\apache-maven-3.9.6\bin\mvn.cmd`。
- 网关模块：`netsight-gateway-agent`，Spring Boot 4.0.8，Java 21，Jackson 3 使用 `tools.jackson.databind.*`。
- 云端模块：`netsight-server`，Spring Boot 4.0.8，MyBatis-Plus，逻辑删除值固定 `2`，未删除值 `0`。
- 本地清单唯一权威源：`conf/mapping.json`；`MappingSync` 不再调用云端 `GET /edge/config/mapping`。
- 同步方向：网关 → 云端单向；云端不回写设备配置。
- 设备 CRUD 只在网关本地页面进行；云端设备新增、编辑、删除全部拒绝，平台超管也不保留应用内入口。
- 同步周期默认 300 秒；本地增删改成功后立即同步；失败退避 15/30/60/120/300 秒。
- 空清单默认拒绝；只有本地页面“清空全部”二次确认后才允许 `allowEmpty=true`。
- `collectType` 固定 `ping`，`collectPort` 固定 `0`。
- 设备编码：创建时允许手工填写或留空自动生成，格式 `DEV-<gatewayCode>-<4位序号>`；创建成功后不可修改，历史编码保持原值。
- 云端 `device.del_flag` 软删除值为 `2`；软删除设备重新出现时恢复为 `0`。
- 云端索引从现有全局 `uk_device_code` 调整为 `uk_gateway_device (gateway_id, device_code)`。
- 所有 HTTP 接口统一返回 `R` 包装；网关本地接口使用 `com.netsight.gateway.common.R`，云端使用 `com.netsight.common.core.R`。
- 测试不连真实数据库；服务层测试使用 Mockito，文件存储测试使用 JUnit `@TempDir`。
- 构建命令统一设置 `$env:JAVA_HOME='D:\DevTools\jdk-21'` 后调用 `mvn.cmd`。

---

## 文件结构

### 网关新增

- `netsight-gateway-agent/src/main/java/com/netsight/gateway/sync/MappingDevice.java`：本地清单设备模型，供存储、同步、快照共用。
- `netsight-gateway-agent/src/main/java/com/netsight/gateway/sync/LocalInventoryDocument.java`：`mapping.json` 文档模型。
- `netsight-gateway-agent/src/main/java/com/netsight/gateway/sync/LocalInventoryStore.java`：清单读取、原子写、revision、备份。
- `netsight-gateway-agent/src/main/java/com/netsight/gateway/sync/InventorySync.java`：全量推送、退避、409 重试、同步状态。
- `netsight-gateway-agent/src/main/java/com/netsight/gateway/localapi/LocalDeviceRequest.java`：本地设备新增/编辑请求体。
- `netsight-gateway-agent/src/main/java/com/netsight/gateway/localapi/LocalDeviceService.java`：校验、编码生成、CRUD、触发同步。
- `netsight-gateway-agent/src/test/java/com/netsight/gateway/sync/LocalInventoryStoreTest.java`
- `netsight-gateway-agent/src/test/java/com/netsight/gateway/sync/MappingSyncTest.java`
- `netsight-gateway-agent/src/test/java/com/netsight/gateway/sync/InventorySyncTest.java`
- `netsight-gateway-agent/src/test/java/com/netsight/gateway/localapi/LocalDeviceServiceTest.java`

### 网关修改

- `netsight-gateway-agent/src/main/java/com/netsight/gateway/sync/MappingSync.java`：删除云端拉取，改为本地文件加载，暴露 revision/updatedAt。
- `netsight-gateway-agent/src/main/java/com/netsight/gateway/connector/CloudClient.java`：新增 `syncDevices`，保留旧 `fetchMapping` 只读兼容。
- `netsight-gateway-agent/src/main/java/com/netsight/gateway/connector/HeartbeatService.java`：心跳带清单同步字段。
- `netsight-gateway-agent/src/main/java/com/netsight/gateway/core/RuntimeState.java`：清单同步状态字段。
- `netsight-gateway-agent/src/main/java/com/netsight/gateway/core/ConfigHolder.java`：`local.inventory_sync_interval` 默认 300 秒。
- `netsight-gateway-agent/src/main/java/com/netsight/gateway/localapi/LocalApiController.java`：设备 CRUD、清空、立即同步、同步状态。
- `netsight-gateway-agent/src/main/java/com/netsight/gateway/snapshot/SnapshotCollector.java`：`MappingDevice` 类型引用改为新类。
- `netsight-gateway-agent/src/main/resources/static/index.html`：设备管理页签。
- `netsight-gateway-agent/src/main/resources/static/assets/app.js`：设备 CRUD 交互。

### 云端新增

- `netsight-server/sql/netsight-upgrade-v1.3.2-edge-inventory-sync.sql`
- `netsight-server/src/main/java/com/netsight/modules/system/dto/DeviceInventorySyncRequest.java`
- `netsight-server/src/main/java/com/netsight/modules/system/dto/DeviceInventorySyncResult.java`
- `netsight-server/src/main/java/com/netsight/modules/system/service/DeviceInventorySyncService.java`
- `netsight-server/src/main/java/com/netsight/common/exception/StaleRevisionException.java`
- `netsight-server/src/test/java/com/netsight/modules/system/service/DeviceInventorySyncServiceTest.java`
- `netsight-server/src/test/java/com/netsight/modules/system/service/DeviceServiceReadOnlyTest.java`

### 云端修改

- `netsight-server/src/main/java/com/netsight/modules/system/entity/Device.java`：`source`、`syncedAt`。
- `netsight-server/src/main/java/com/netsight/modules/system/entity/EdgeGateway.java`：清单同步字段。
- `netsight-server/src/main/java/com/netsight/modules/system/mapper/DeviceMapper.java`：含已删除行的查询、恢复。
- `netsight-server/src/main/java/com/netsight/modules/system/controller/EdgeConfigController.java`：新增同步接口。
- `netsight-server/src/main/java/com/netsight/modules/system/controller/DeviceController.java`：写接口拒绝。
- `netsight-server/src/main/java/com/netsight/modules/system/service/DeviceService.java`：写方法统一拒绝。
- `netsight-ui/src/views/device/index.vue`：移除新增/编辑/删除，增加来源/同步时间。

---

### Task 1: 本地清单文档与原子存储

**Files:**
- Create: `netsight-gateway-agent/src/main/java/com/netsight/gateway/sync/MappingDevice.java`
- Create: `netsight-gateway-agent/src/main/java/com/netsight/gateway/sync/LocalInventoryDocument.java`
- Create: `netsight-gateway-agent/src/main/java/com/netsight/gateway/sync/LocalInventoryStore.java`
- Test: `netsight-gateway-agent/src/test/java/com/netsight/gateway/sync/LocalInventoryStoreTest.java`

**Interfaces:**
- Consumes: `ConfigHolder.getConfig().getMappingPath()`、`ConfigHolder.getConfDir()`。
- Produces:
  - `MappingDevice`：`getDeviceCode()/getDeviceName()/getDeviceIp()/getDeviceType()/getLocation()/getBrand()/getModel()/getCollectType()/getCollectPort()/getWarrantyExpire()/getLabels()`
  - `LocalInventoryDocument`：`getVersion()/getRevision()/getUpdatedAt()/getDevices()`
  - `LocalInventoryStore`：`read()`、`mutate(Function<LocalInventoryDocument,T>)`、`bumpRevisionTo(long)`、`getRevision()`、`getDeviceCount()`、`getPath()`

- [ ] **Step 1: 写失败测试**

```java
package com.netsight.gateway.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LocalInventoryStoreTest {

    @TempDir
    Path temp;

    @Test
    void createsEmptyDocumentWhenFileMissing() {
        LocalInventoryStore store = new LocalInventoryStore(temp.resolve("mapping.json"));
        LocalInventoryDocument doc = store.read();

        assertEquals(1, doc.getRevision());
        assertNotNull(doc.getDevices());
        assertTrue(Files.exists(temp.resolve("mapping.json")));
    }

    @Test
    void mutateIncrementsRevisionAndWritesDevice() {
        LocalInventoryStore store = new LocalInventoryStore(temp.resolve("mapping.json"));
        MappingDevice device = new MappingDevice();
        device.setDeviceCode("DEV-GW1-0001");
        device.setDeviceName("测试设备");
        device.setDeviceIp("10.0.0.1");
        device.setDeviceType("camera");

        store.mutate(doc -> {
            doc.getDevices().add(device);
            return null;
        });

        LocalInventoryDocument after = store.read();
        assertEquals(2, after.getRevision());
        assertEquals(1, after.getDevices().size());
        assertEquals("DEV-GW1-0001", after.getDevices().get(0).getDeviceCode());
    }

    @Test
    void malformedDevicesFieldIsRejected() throws Exception {
        Path file = temp.resolve("mapping.json");
        Files.writeString(file, "{\"revision\":3,\"devices\":\"not-array\"}");
        LocalInventoryStore store = new LocalInventoryStore(file);

        assertThrows(IllegalStateException.class, store::read);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
$env:JAVA_HOME='D:\DevTools\jdk-21'
& 'D:\DevTools\apache-maven-3.9.6\bin\mvn.cmd' -f netsight-gateway-agent/pom.xml -Dtest=LocalInventoryStoreTest test
```

Expected: FAIL，提示找不到 `LocalInventoryStore`、`MappingDevice`。

- [ ] **Step 3: 写模型与存储实现**

`MappingDevice.java`：

```java
package com.netsight.gateway.sync;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class MappingDevice {
    private String deviceCode;
    private String deviceName;
    private String deviceIp;
    private String deviceType;
    private String location;
    private String brand;
    private String model;
    private String collectType = "ping";
    private int collectPort = 0;
    private String warrantyExpire;
    private Map<String, String> labels = new LinkedHashMap<>();
}
```

`LocalInventoryDocument.java`：

```java
package com.netsight.gateway.sync;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class LocalInventoryDocument {
    private String version;
    private long revision = 1;
    private String updatedAt;
    private List<MappingDevice> devices = new ArrayList<>();
}
```

`LocalInventoryStore.java`：

```java
package com.netsight.gateway.sync;

import com.netsight.gateway.core.ConfigHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.function.Function;
import java.util.stream.Stream;

@Slf4j
@Component
public class LocalInventoryStore {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int KEEP_BACKUPS = 20;

    private final Path path;
    private final ObjectMapper mapper;
    private final Object lock = new Object();

    public LocalInventoryStore(ConfigHolder configHolder) {
        this(resolve(configHolder), new ObjectMapper());
    }

    LocalInventoryStore(Path path) {
        this(path, new ObjectMapper());
    }

    LocalInventoryStore(Path path, ObjectMapper mapper) {
        this.path = path;
        this.mapper = mapper;
    }

    public LocalInventoryDocument read() {
        synchronized (lock) {
            try {
                if (!Files.exists(path)) {
                    LocalInventoryDocument doc = newDocument();
                    write(doc);
                    return doc;
                }
                String json = Files.readString(path);
                JsonNode root = mapper.readTree(json);
                if (!root.path("devices").isArray()) {
                    throw new IllegalStateException("mapping.json 的 devices 字段不是数组: " + path);
                }
                LocalInventoryDocument doc = mapper.convertValue(root, LocalInventoryDocument.class);
                if (doc.getRevision() <= 0) {
                    doc.setRevision(1);
                }
                if (doc.getDevices() == null) {
                    doc.setDevices(new java.util.ArrayList<>());
                }
                return doc;
            } catch (IOException e) {
                throw new IllegalStateException("读取 mapping.json 失败: " + path, e);
            }
        }
    }

    public <T> T mutate(Function<LocalInventoryDocument, T> operator) {
        synchronized (lock) {
            LocalInventoryDocument doc = read();
            T result = (T) operator.apply(doc);
            doc.setRevision(doc.getRevision() + 1);
            doc.setUpdatedAt(LocalDateTime.now().format(TS));
            write(doc);
            return result;
        }
    }

    public void bumpRevisionTo(long minRevision) {
        synchronized (lock) {
            LocalInventoryDocument doc = read();
            if (doc.getRevision() < minRevision) {
                doc.setRevision(minRevision);
                doc.setUpdatedAt(LocalDateTime.now().format(TS));
                write(doc);
            }
        }
    }

    public long getRevision() {
        return read().getRevision();
    }

    public int getDeviceCount() {
        return read().getDevices().size();
    }

    public Path getPath() {
        return path;
    }

    private LocalInventoryDocument newDocument() {
        LocalInventoryDocument doc = new LocalInventoryDocument();
        doc.setVersion("local-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")));
        doc.setRevision(1);
        doc.setUpdatedAt(LocalDateTime.now().format(TS));
        return doc;
    }

    private void write(LocalInventoryDocument doc) {
        try {
            Files.createDirectories(path.getParent());
            backupIfExists();
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(doc);
            Files.writeString(tmp, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("写入 mapping.json 失败: " + path, e);
        }
    }

    private void backupIfExists() throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Path backup = path.resolveSibling(path.getFileName() + ".bak-" + LocalDateTime.now().format(DAY));
        Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING);
        try (Stream<Path> stream = Files.list(path.getParent())) {
            stream.filter(p -> p.getFileName().toString().startsWith(path.getFileName() + ".bak-"))
                    .sorted(Comparator.comparing(Path::getFileName).reversed())
                    .skip(KEEP_BACKUPS)
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            log.warn("清理清单备份失败: {}", p, e);
                        }
                    });
        }
    }

    private static Path resolve(ConfigHolder configHolder) {
        String mp = configHolder.getConfig() == null ? null : configHolder.getConfig().getMappingPath();
        if (mp != null && !mp.isBlank()) {
            return Paths.get(mp);
        }
        return Paths.get(configHolder.getConfDir(), "mapping.json");
    }
}
```

注意：`mutate` 的 `operator` 是 `Function`，调用方返回 `null` 即可；如果需要返回设备对象，使用 `mutate(doc -> { ...; return device; })`。

- [ ] **Step 4: 运行测试确认通过**

Run:

```powershell
$env:JAVA_HOME='D:\DevTools\jdk-21'
& 'D:\DevTools\apache-maven-3.9.6\bin\mvn.cmd' -f netsight-gateway-agent/pom.xml -Dtest=LocalInventoryStoreTest test
```

Expected: PASS。

- [ ] **Step 5: 提交**

```powershell
git add netsight-gateway-agent/src/main/java/com/netsight/gateway/sync/MappingDevice.java `
  netsight-gateway-agent/src/main/java/com/netsight/gateway/sync/LocalInventoryDocument.java `
  netsight-gateway-agent/src/main/java/com/netsight/gateway/sync/LocalInventoryStore.java `
  netsight-gateway-agent/src/test/java/com/netsight/gateway/sync/LocalInventoryStoreTest.java
git commit -m "feat(gateway): 增加设备清单本地存储与原子写入"
```

---

### Task 2: MappingSync 改为本地清单加载

**Files:**
- Modify: `netsight-gateway-agent/src/main/java/com/netsight/gateway/sync/MappingSync.java`
- Modify: `netsight-gateway-agent/src/main/java/com/netsight/gateway/snapshot/SnapshotCollector.java`
- Test: `netsight-gateway-agent/src/test/java/com/netsight/gateway/sync/MappingSyncTest.java`

**Interfaces:**
- Consumes: `LocalInventoryStore.read()`、`MappingDevice`。
- Produces:
  - `MappingSync.reloadLocal(): String`
  - `MappingSync.getDevices(): List<MappingDevice>`
  - `MappingSync.getRevision(): long`
  - `MappingSync.getUpdatedAt(): String`

- [ ] **Step 1: 写失败测试**

```java
package com.netsight.gateway.sync;

import com.netsight.gateway.core.ConfigHolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MappingSyncTest {

    @TempDir
    Path temp;

    @Test
    void reloadLocalWritesPingTargets() throws Exception {
        Path mapping = temp.resolve("mapping.json");
        LocalInventoryStore store = new LocalInventoryStore(mapping);
        MappingDevice device = new MappingDevice();
        device.setDeviceCode("DEV-GW1-0001");
        device.setDeviceName("测试设备");
        device.setDeviceIp("10.0.0.1");
        device.setDeviceType("camera");
        store.mutate(doc -> { doc.getDevices().add(device); return null; });

        ConfigHolder holder = mock(ConfigHolder.class);
        when(holder.getTargetsDir()).thenReturn(temp.resolve("targets").toString());
        MappingSync sync = new MappingSync(holder, store, new com.netsight.gateway.core.RuntimeState());

        sync.reloadLocal();

        String ping = Files.readString(temp.resolve("targets").resolve("ping.json"));
        assertTrue(ping.contains("10.0.0.1"));
        assertEquals(1, sync.getDevices().size());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```powershell
$env:JAVA_HOME='D:\DevTools\jdk-21'
& 'D:\DevTools\apache-maven-3.9.6\bin\mvn.cmd' -f netsight-gateway-agent/pom.xml -Dtest=MappingSyncTest test
```

Expected: FAIL，`MappingSync` 构造器或 `reloadLocal` 不存在。

- [ ] **Step 3: 重写 MappingSync**

```java
package com.netsight.gateway.sync;

import com.netsight.gateway.core.ConfigHolder;
import com.netsight.gateway.core.RuntimeState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class MappingSync {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ConfigHolder configHolder;
    private final LocalInventoryStore store;
    private final RuntimeState runtimeState;
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile List<MappingDevice> devices = new ArrayList<>();
    private volatile long lastFileMtime = 0L;
    private volatile String version = "-";
    private volatile long revision = 0L;
    private volatile String updatedAt = "-";

    public MappingSync(ConfigHolder configHolder, LocalInventoryStore store, RuntimeState runtimeState) {
        this.configHolder = configHolder;
        this.store = store;
        this.runtimeState = runtimeState;
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        reloadLocal();
    }

    @Scheduled(fixedDelay = 60000)
    public void scheduledSync() {
        try {
            Path p = store.getPath();
            long mtime = Files.exists(p) ? Files.getLastModifiedTime(p).toMillis() : 0L;
            if (mtime != lastFileMtime || devices.isEmpty()) {
                reloadLocal();
            }
        } catch (Exception e) {
            runtimeState.setLastSyncResult(now() + " 本地清单检测失败: " + e.getMessage());
            log.error("本地清单检测失败", e);
        }
    }

    public synchronized String reloadLocal() {
        try {
            LocalInventoryDocument doc = store.read();
            this.devices = new ArrayList<>(doc.getDevices());
            this.version = doc.getVersion();
            this.revision = doc.getRevision();
            this.updatedAt = doc.getUpdatedAt();
            writeTargets();
            this.lastFileMtime = Files.exists(store.getPath())
                    ? Files.getLastModifiedTime(store.getPath()).toMillis() : 0L;
            String msg = String.format("本地清单已加载（version=%s，revision=%d，设备 %d 台）",
                    version, revision, devices.size());
            runtimeState.setLastSyncResult(now() + " " + msg);
            log.info("{}", msg);
            return msg;
        } catch (Exception e) {
            String msg = "本地清单加载失败: " + e.getMessage();
            runtimeState.setLastSyncResult(now() + " " + msg);
            log.error("{}", msg, e);
            return msg;
        }
    }

    private void writeTargets() throws Exception {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (MappingDevice d : devices) {
            if (d.getDeviceIp() == null || d.getDeviceIp().isBlank()) {
                continue;
            }
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("targets", List.of(d.getDeviceIp()));
            Map<String, String> labels = new LinkedHashMap<>(d.getLabels());
            labels.put("device_code", d.getDeviceCode());
            labels.put("device_name", d.getDeviceName());
            labels.put("device_ip", d.getDeviceIp());
            labels.put("device_type", d.getDeviceType());
            if (d.getLocation() != null && !d.getLocation().isBlank()) {
                labels.put("device_location", d.getLocation());
            }
            e.put("labels", labels);
            entries.add(e);
        }
        Path dir = Paths.get(configHolder.getTargetsDir());
        Files.createDirectories(dir);
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(entries);
        Files.writeString(dir.resolve("ping.json"), json);
    }

    public List<MappingDevice> getDevices() {
        return devices;
    }

    public long getRevision() {
        return revision;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public String getVersion() {
        return version;
    }

    private static String now() {
        return LocalDateTime.now().format(FMT);
    }
}
```

`SnapshotCollector.java` 需要把 `MappingSync.MappingDevice` 全部替换为 `MappingDevice`，并在 import 区加入：

```java
import com.netsight.gateway.sync.MappingDevice;
```

- [ ] **Step 4: 运行测试确认通过**

```powershell
$env:JAVA_HOME='D:\DevTools\jdk-21'
& 'D:\DevTools\apache-maven-3.9.6\bin\mvn.cmd' -f netsight-gateway-agent/pom.xml -Dtest=MappingSyncTest test
```

Expected: PASS。

- [ ] **Step 5: 提交**

```powershell
git add netsight-gateway-agent/src/main/java/com/netsight/gateway/sync/MappingSync.java `
  netsight-gateway-agent/src/main/java/com/netsight/gateway/snapshot/SnapshotCollector.java `
  netsight-gateway-agent/src/test/java/com/netsight/gateway/sync/MappingSyncTest.java
git commit -m "refactor(gateway): 清单加载改为本地文件权威源"
```

---

### Task 3: 网关本地设备 CRUD 服务与 API

**Files:**
- Create: `netsight-gateway-agent/src/main/java/com/netsight/gateway/localapi/LocalDeviceRequest.java`
- Create: `netsight-gateway-agent/src/main/java/com/netsight/gateway/localapi/LocalDeviceService.java`
- Modify: `netsight-gateway-agent/src/main/java/com/netsight/gateway/localapi/LocalApiController.java`
- Test: `netsight-gateway-agent/src/test/java/com/netsight/gateway/localapi/LocalDeviceServiceTest.java`

**Interfaces:**
- Consumes: `LocalInventoryStore`、`MappingSync`、`InventorySync`、`ConfigHolder`。
- Produces:
  - `LocalDeviceService.create(LocalDeviceRequest): MappingDevice`
  - `LocalDeviceService.update(String deviceCode, LocalDeviceRequest): MappingDevice`
  - `LocalDeviceService.delete(String deviceCode, boolean confirmEmpty): void`
  - `LocalDeviceService.clearAll(String confirm): void`
  - `LocalDeviceService.syncNow(): String`

- [ ] **Step 1: 写失败测试**

```java
package com.netsight.gateway.localapi;

import com.netsight.gateway.core.ConfigHolder;
import com.netsight.gateway.sync.InventorySync;
import com.netsight.gateway.sync.LocalInventoryStore;
import com.netsight.gateway.sync.MappingDevice;
import com.netsight.gateway.sync.MappingSync;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LocalDeviceServiceTest {

    @TempDir
    Path temp;

    @Test
    void createsDeviceWithGeneratedCodeAndTriggersSync() {
        ConfigHolder holder = mock(ConfigHolder.class);
        ConfigHolder.GatewayConfig cfg = new ConfigHolder.GatewayConfig();
        cfg.setGatewayCode("GW-TEST-01");
        when(holder.getConfig()).thenReturn(cfg);

        LocalInventoryStore store = new LocalInventoryStore(temp.resolve("mapping.json"));
        MappingSync mappingSync = mock(MappingSync.class);
        InventorySync inventorySync = mock(InventorySync.class);
        LocalDeviceService service = new LocalDeviceService(holder, store, mappingSync, inventorySync);

        LocalDeviceRequest req = new LocalDeviceRequest();
        req.setDeviceName("测试枪机");
        req.setDeviceIp("10.0.0.1");
        req.setDeviceType("camera");

        MappingDevice device = service.create(req);

        assertEquals("DEV-GW-TEST-01-0001", device.getDeviceCode());
        assertEquals("ping", device.getCollectType());
        assertEquals(0, device.getCollectPort());
        verify(mappingSync).reloadLocal();
        verify(inventorySync).triggerNow(false);
    }

    @Test
    void rejectsInvalidIp() {
        ConfigHolder holder = mock(ConfigHolder.class);
        ConfigHolder.GatewayConfig cfg = new ConfigHolder.GatewayConfig();
        cfg.setGatewayCode("GW-TEST-01");
        when(holder.getConfig()).thenReturn(cfg);

        LocalInventoryStore store = new LocalInventoryStore(temp.resolve("mapping.json"));
        LocalDeviceService service = new LocalDeviceService(holder, store, mock(MappingSync.class), mock(InventorySync.class));

        LocalDeviceRequest req = new LocalDeviceRequest();
        req.setDeviceName("坏IP");
        req.setDeviceIp("10.0.0");
        req.setDeviceType("camera");

        assertThrows(IllegalArgumentException.class, () -> service.create(req));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```powershell
$env:JAVA_HOME='D:\DevTools\jdk-21'
& 'D:\DevTools\apache-maven-3.9.6\bin\mvn.cmd' -f netsight-gateway-agent/pom.xml -Dtest=LocalDeviceServiceTest test
```

Expected: FAIL，服务类不存在。

- [ ] **Step 3: 写请求体与服务**

`LocalDeviceRequest.java`：

```java
package com.netsight.gateway.localapi;

import lombok.Data;

@Data
public class LocalDeviceRequest {
    private String deviceCode;
    private String deviceName;
    private String deviceIp;
    private String deviceType;
    private String location;
    private String brand;
    private String model;
    private String warrantyExpire;
}
```

`LocalDeviceService.java`：

```java
package com.netsight.gateway.localapi;

import com.netsight.gateway.core.ConfigHolder;
import com.netsight.gateway.sync.InventorySync;
import com.netsight.gateway.sync.LocalInventoryStore;
import com.netsight.gateway.sync.MappingDevice;
import com.netsight.gateway.sync.MappingSync;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class LocalDeviceService {

    private static final Set<String> TYPES = Set.of("network", "camera", "nvr", "door_controller");
    private static final String IPV4 = "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$";
    private static final String CLEAR_CONFIRM = "清空全部";

    private final ConfigHolder configHolder;
    private final LocalInventoryStore store;
    private final MappingSync mappingSync;
    private final InventorySync inventorySync;

    public List<MappingDevice> list() {
        return store.read().getDevices();
    }

    public MappingDevice create(LocalDeviceRequest req) {
        validate(req);
        String code = StringUtils.hasText(req.getDeviceCode()) ? req.getDeviceCode().trim() : nextCode();
        validateCode(code);
        if (find(code) != null) {
            throw new IllegalArgumentException("设备编码已存在: " + code);
        }
        MappingDevice device = toDevice(req, code);
        store.mutate(doc -> {
            doc.getDevices().add(device);
            return null;
        });
        refreshAndSync(false);
        return device;
    }

    public MappingDevice update(String deviceCode, LocalDeviceRequest req) {
        validate(req);
        MappingDevice exist = find(deviceCode);
        if (exist == null) {
            throw new IllegalArgumentException("设备不存在: " + deviceCode);
        }
        store.mutate(doc -> {
            MappingDevice target = doc.getDevices().stream()
                    .filter(d -> deviceCode.equals(d.getDeviceCode())).findFirst().orElseThrow();
            target.setDeviceName(req.getDeviceName().trim());
            target.setDeviceIp(req.getDeviceIp().trim());
            target.setDeviceType(req.getDeviceType());
            target.setLocation(trim(req.getLocation()));
            target.setBrand(trim(req.getBrand()));
            target.setModel(trim(req.getModel()));
            target.setWarrantyExpire(trim(req.getWarrantyExpire()));
            target.setCollectType("ping");
            target.setCollectPort(0);
            return null;
        });
        refreshAndSync(false);
        return find(deviceCode);
    }

    public void delete(String deviceCode, boolean confirmEmpty) {
        MappingDevice exist = find(deviceCode);
        if (exist == null) {
            throw new IllegalArgumentException("设备不存在: " + deviceCode);
        }
        boolean last = store.getDeviceCount() == 1;
        if (last && !confirmEmpty) {
            throw new IllegalArgumentException("删除最后一台设备需要在页面确认“清空全部”");
        }
        store.mutate(doc -> {
            doc.getDevices().removeIf(d -> deviceCode.equals(d.getDeviceCode()));
            return null;
        });
        refreshAndSync(last);
    }

    public void clearAll(String confirm) {
        if (!CLEAR_CONFIRM.equals(confirm)) {
            throw new IllegalArgumentException("确认短语不正确，必须输入“清空全部”");
        }
        store.mutate(doc -> {
            doc.getDevices().clear();
            return null;
        });
        refreshAndSync(true);
    }

    public String syncNow() {
        mappingSync.reloadLocal();
        inventorySync.triggerNow(false);
        return "已触发同步";
    }

    private void refreshAndSync(boolean allowEmpty) {
        mappingSync.reloadLocal();
        inventorySync.triggerNow(allowEmpty);
    }

    private MappingDevice find(String code) {
        return store.read().getDevices().stream()
                .filter(d -> code.equals(d.getDeviceCode()))
                .findFirst().orElse(null);
    }

    private String nextCode() {
        String gatewayCode = configHolder.getConfig().getGatewayCode();
        String prefix = "DEV-" + gatewayCode + "-";
        int max = 0;
        for (MappingDevice d : store.read().getDevices()) {
            String code = d.getDeviceCode();
            if (code != null && code.startsWith(prefix)) {
                try {
                    max = Math.max(max, Integer.parseInt(code.substring(prefix.length())));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return prefix + String.format("%04d", max + 1);
    }

    private void validateCode(String code) {
        if (!StringUtils.hasText(code) || code.length() > 64 || !code.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("设备编码只允许字母、数字、-、_，最大 64 字符");
        }
    }

    private MappingDevice toDevice(LocalDeviceRequest req, String code) {
        MappingDevice d = new MappingDevice();
        d.setDeviceCode(code);
        d.setDeviceName(req.getDeviceName().trim());
        d.setDeviceIp(req.getDeviceIp().trim());
        d.setDeviceType(req.getDeviceType());
        d.setLocation(trim(req.getLocation()));
        d.setBrand(trim(req.getBrand()));
        d.setModel(trim(req.getModel()));
        d.setWarrantyExpire(trim(req.getWarrantyExpire()));
        d.setCollectType("ping");
        d.setCollectPort(0);
        return d;
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static void validate(LocalDeviceRequest req) {
        if (!StringUtils.hasText(req.getDeviceName())) {
            throw new IllegalArgumentException("设备名称不能为空");
        }
        if (!StringUtils.hasText(req.getDeviceIp()) || !req.getDeviceIp().trim().matches(IPV4)) {
            throw new IllegalArgumentException("管理 IP 必须是合法 IPv4");
        }
        if (req.getDeviceType() == null || !TYPES.contains(req.getDeviceType())) {
            throw new IllegalArgumentException("设备类型不合法");
        }
        if (StringUtils.hasText(req.getWarrantyExpire())) {
            try {
                LocalDate.parse(req.getWarrantyExpire().trim());
            } catch (Exception e) {
                throw new IllegalArgumentException("保修截止日期格式必须为 YYYY-MM-DD");
            }
        }
    }
}
```

校验方法直接写在 `LocalDeviceService` 内，不保留间接层。

- [ ] **Step 4: 扩展 LocalApiController**

在现有构造器注入 `LocalDeviceService` 和 `LocalInventoryStore`，替换 `devices()`，并新增接口：

```java
@GetMapping("/devices")
public R<Map<String, Object>> devices() {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("revision", store.getRevision());
    data.put("updatedAt", store.read().getUpdatedAt());
    data.put("syncStatus", runtimeState.getInventorySyncStatus());
    data.put("lastSyncAt", runtimeState.getLastInventorySyncAt());
    data.put("nextRetryAt", runtimeState.getNextInventoryRetryAt());
    List<Map<String, Object>> items = new ArrayList<>();
    for (MappingDevice d : mappingSync.getDevices()) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("deviceCode", d.getDeviceCode());
        item.put("deviceName", d.getDeviceName());
        item.put("deviceIp", d.getDeviceIp());
        item.put("deviceType", d.getDeviceType());
        item.put("location", d.getLocation());
        item.put("brand", d.getBrand());
        item.put("model", d.getModel());
        item.put("warrantyExpire", d.getWarrantyExpire());
        item.put("collectType", d.getCollectType());
        item.put("collectPort", d.getCollectPort());
        RuntimeState.DeviceState st = runtimeState.getDeviceStates().get(d.getDeviceCode());
        item.put("up", st == null ? null : st.getUp());
        item.put("lineAbnormal", st == null ? null : st.getLineAbnormal());
        item.put("collectedAt", st == null ? "-" : st.getCollectedAt());
        items.add(item);
    }
    data.put("items", items);
    return R.ok(data);
}

@PostMapping("/devices")
public R<MappingDevice> addDevice(@RequestBody LocalDeviceRequest req) {
    try {
        return R.ok(localDeviceService.create(req));
    } catch (IllegalArgumentException e) {
        return R.fail(ResultCode.BAD_REQUEST, e.getMessage());
    }
}

@PutMapping("/devices/{deviceCode}")
public R<MappingDevice> updateDevice(@PathVariable String deviceCode, @RequestBody LocalDeviceRequest req) {
    try {
        return R.ok(localDeviceService.update(deviceCode, req));
    } catch (IllegalArgumentException e) {
        return R.fail(ResultCode.BAD_REQUEST, e.getMessage());
    }
}

@DeleteMapping("/devices/{deviceCode}")
public R<Void> deleteDevice(@PathVariable String deviceCode,
                            @RequestParam(defaultValue = "false") boolean confirmEmpty) {
    try {
        localDeviceService.delete(deviceCode, confirmEmpty);
        return R.ok(null);
    } catch (IllegalArgumentException e) {
        return R.fail(ResultCode.BAD_REQUEST, e.getMessage());
    }
}

@PostMapping("/devices/clear")
public R<Void> clearDevices(@RequestBody Map<String, String> body) {
    try {
        localDeviceService.clearAll(body.getOrDefault("confirm", ""));
        return R.ok(null);
    } catch (IllegalArgumentException e) {
        return R.fail(ResultCode.BAD_REQUEST, e.getMessage());
    }
}

@PostMapping("/devices/sync")
public R<String> syncDevices() {
    return R.ok(localDeviceService.syncNow());
}

@GetMapping("/devices/sync-status")
public R<Map<String, Object>> syncStatus() {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("status", runtimeState.getInventorySyncStatus());
    data.put("lastSyncAt", runtimeState.getLastInventorySyncAt());
    data.put("result", runtimeState.getLastInventorySyncResult());
    data.put("nextRetryAt", runtimeState.getNextInventoryRetryAt());
    data.put("failCount", runtimeState.getInventorySyncFailCount());
    return R.ok(data);
}
```

- [ ] **Step 5: 运行测试确认通过**

```powershell
$env:JAVA_HOME='D:\DevTools\jdk-21'
& 'D:\DevTools\apache-maven-3.9.6\bin\mvn.cmd' -f netsight-gateway-agent/pom.xml -Dtest=LocalDeviceServiceTest test
```

Expected: PASS。

- [ ] **Step 6: 提交**

```powershell
git add netsight-gateway-agent/src/main/java/com/netsight/gateway/localapi/LocalDeviceRequest.java `
  netsight-gateway-agent/src/main/java/com/netsight/gateway/localapi/LocalDeviceService.java `
  netsight-gateway-agent/src/main/java/com/netsight/gateway/localapi/LocalApiController.java `
  netsight-gateway-agent/src/test/java/com/netsight/gateway/localapi/LocalDeviceServiceTest.java
git commit -m "feat(gateway): 本地页面设备增删改接口"
```

---

### Task 4: InventorySync 全量推送、退避与状态

**Files:**
- Modify: `netsight-gateway-agent/src/main/java/com/netsight/gateway/connector/CloudClient.java`
- Create: `netsight-gateway-agent/src/main/java/com/netsight/gateway/sync/InventorySync.java`
- Modify: `netsight-gateway-agent/src/main/java/com/netsight/gateway/core/RuntimeState.java`
- Modify: `netsight-gateway-agent/src/main/java/com/netsight/gateway/core/ConfigHolder.java`
- Modify: `netsight-gateway-agent/src/main/java/com/netsight/gateway/connector/HeartbeatService.java`
- Test: `netsight-gateway-agent/src/test/java/com/netsight/gateway/sync/InventorySyncTest.java`

**Interfaces:**
- Consumes: `LocalInventoryStore.read()`、`CloudClient.syncDevices(String)`、`RuntimeState`。
- Produces:
  - `CloudClient.SyncResponse(int httpStatus, String body)`
  - `CloudClient.syncDevices(String body): SyncResponse`
  - `InventorySync.triggerNow()`、`InventorySync.triggerNow(boolean allowEmpty)`
  - `InventorySync.syncOnce(boolean allowEmpty)`（包内可见，供测试）
  - `RuntimeState.getInventorySyncStatus()/getLastInventorySyncAt()/getNextInventoryRetryAt()/getInventorySyncFailCount()`

- [ ] **Step 1: 写失败测试**

```java
package com.netsight.gateway.sync;

import com.netsight.gateway.connector.CloudClient;
import com.netsight.gateway.core.ConfigHolder;
import com.netsight.gateway.core.RuntimeState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class InventorySyncTest {

    @TempDir
    Path temp;

    @Test
    void sendsFullBodyWithRevisionAndDeviceCount() {
        ConfigHolder holder = mock(ConfigHolder.class);
        ConfigHolder.GatewayConfig cfg = new ConfigHolder.GatewayConfig();
        cfg.setGatewayCode("GW-TEST-01");
        cfg.setLocal(new ConfigHolder.Local());
        when(holder.getConfig()).thenReturn(cfg);

        LocalInventoryStore store = new LocalInventoryStore(temp.resolve("mapping.json"));
        MappingDevice device = new MappingDevice();
        device.setDeviceCode("DEV-GW-TEST-01-0001");
        device.setDeviceName("测试枪机");
        device.setDeviceIp("10.0.0.1");
        device.setDeviceType("camera");
        store.mutate(doc -> { doc.getDevices().add(device); return null; });
        long revision = store.getRevision();

        CloudClient cloud = mock(CloudClient.class);
        when(cloud.syncDevices(anyString()))
                .thenReturn(new CloudClient.SyncResponse(200, "{\"code\":200,\"data\":{}}"));
        RuntimeState state = new RuntimeState();
        InventorySync sync = new InventorySync(holder, store, cloud, state);

        sync.syncOnce(false);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(cloud).syncDevices(captor.capture());
        assertTrue(captor.getValue().contains("\"revision\":" + revision));
        assertTrue(captor.getValue().contains("\"deviceCode\":\"DEV-GW-TEST-01-0001\""));
        assertEquals("success", state.getInventorySyncStatus());
    }

    @Test
    void blocksEmptyListWithoutAllowEmpty() {
        ConfigHolder holder = mock(ConfigHolder.class);
        ConfigHolder.GatewayConfig cfg = new ConfigHolder.GatewayConfig();
        cfg.setGatewayCode("GW-TEST-01");
        cfg.setLocal(new ConfigHolder.Local());
        when(holder.getConfig()).thenReturn(cfg);

        LocalInventoryStore store = new LocalInventoryStore(temp.resolve("mapping.json"));
        CloudClient cloud = mock(CloudClient.class);
        RuntimeState state = new RuntimeState();
        InventorySync sync = new InventorySync(holder, store, cloud, state);

        sync.syncOnce(false);

        verifyNoInteractions(cloud);
        assertEquals("empty_blocked", state.getInventorySyncStatus());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```powershell
$env:JAVA_HOME='D:\DevTools\jdk-21'
& 'D:\DevTools\apache-maven-3.9.6\bin\mvn.cmd' -f netsight-gateway-agent/pom.xml -Dtest=InventorySyncTest test
```

Expected: FAIL，`InventorySync` 不存在。

- [ ] **Step 3: 扩展 CloudClient**

在 `CloudClient` 中增加：

```java
public SyncResponse syncDevices(String body) {
    String base = base();
    if (base == null) {
        return new SyncResponse(0, "云端 base_url 未配置");
    }
    try {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(base + "/edge/config/devices/sync"))
                .timeout(Duration.ofMillis(configHolder.getConfig().getCloud().getTimeoutMs()))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("X-Gateway-Token", configHolder.getConfig().getCloud().getGatewayToken())
                .header("X-Netsight-Tenant-Id", String.valueOf(tenantId()))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        return new SyncResponse(resp.statusCode(), resp.body());
    } catch (Exception e) {
        log.warn("设备清单同步请求异常: {}", e.getMessage());
        return new SyncResponse(0, e.getMessage());
    }
}

public record SyncResponse(int httpStatus, String body) {
}
```

保留 `fetchMapping()` 原样，旧网关兼容。

- [ ] **Step 4: 写 InventorySync**

```java
package com.netsight.gateway.sync;

import com.netsight.gateway.connector.CloudClient;
import com.netsight.gateway.core.ConfigHolder;
import com.netsight.gateway.core.RuntimeState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class InventorySync {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final long[] BACKOFF_SECONDS = {15, 30, 60, 120, 300};

    private final ConfigHolder configHolder;
    private final LocalInventoryStore store;
    private final CloudClient cloudClient;
    private final RuntimeState runtimeState;
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile boolean pending = true;
    private volatile boolean allowEmptyOnce = false;
    private volatile long nextAttemptAt = 0L;
    private volatile long lastSuccessAt = 0L;
    private int failCount = 0;

    public InventorySync(ConfigHolder configHolder, LocalInventoryStore store,
                         CloudClient cloudClient, RuntimeState runtimeState) {
        this.configHolder = configHolder;
        this.store = store;
        this.cloudClient = cloudClient;
        this.runtimeState = runtimeState;
    }

    public void triggerNow() {
        triggerNow(false);
    }

    public void triggerNow(boolean allowEmpty) {
        if (allowEmpty) {
            allowEmptyOnce = true;
        }
        pending = true;
        nextAttemptAt = 0L;
    }

    @Scheduled(fixedDelay = 15000)
    public void tick() {
        if (!pending && System.currentTimeMillis() < nextAttemptAt) {
            return;
        }
        long interval = intervalMs();
        if (!pending && lastSuccessAt > 0 && System.currentTimeMillis() - lastSuccessAt < interval) {
            return;
        }
        pending = false;
        syncOnce(allowEmptyOnce);
    }

    void syncOnce(boolean allowEmpty) {
        syncOnce(allowEmpty, 0);
    }

    private void syncOnce(boolean allowEmpty, int retryCount) {
        synchronized (this) {
            ConfigHolder.GatewayConfig cfg = configHolder.getConfig();
            LocalInventoryDocument doc = store.read();
            if (doc.getDevices().isEmpty() && !allowEmpty) {
                runtimeState.markInventorySyncBlocked("空清单已阻止，需在本地页面确认“清空全部”");
                nextAttemptAt = System.currentTimeMillis() + intervalMs();
                log.warn("空设备清单已阻止，不向云端发送");
                return;
            }
            String body = buildBody(cfg, doc, allowEmpty);
            CloudClient.SyncResponse resp = cloudClient.syncDevices(body);
            try {
                JsonNode root = mapper.readTree(resp.body());
                int code = root.path("code").asInt(0);
                if (resp.httpStatus() == 200 && code == 200) {
                    failCount = 0;
                    nextAttemptAt = 0L;
                    lastSuccessAt = System.currentTimeMillis();
                    allowEmptyOnce = false;
                    runtimeState.markInventorySyncSuccess(doc.getRevision(), doc.getDevices().size());
                    log.info("设备清单同步成功: revision={}, devices={}", doc.getRevision(), doc.getDevices().size());
                    return;
                }
                if (code == 409) {
                    if (retryCount >= 1) {
                        fail(resp.httpStatus(), resp.body());
                        return;
                    }
                    long current = root.path("data").path("currentRevision").asLong(0);
                    store.bumpRevisionTo(current + 1);
                    log.warn("设备清单版本落后，本地 revision 提升到 {} 后重试", current + 1);
                    syncOnce(allowEmpty, 1);
                    return;
                }
                fail(resp.httpStatus(), resp.body());
            } catch (Exception e) {
                fail(resp.httpStatus(), resp.body());
            }
        }
    }

    private String buildBody(ConfigHolder.GatewayConfig cfg, LocalInventoryDocument doc, boolean allowEmpty) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("gatewayCode", cfg.getGatewayCode());
        body.put("revision", doc.getRevision());
        body.put("updatedAt", doc.getUpdatedAt());
        body.put("full", true);
        body.put("allowEmpty", allowEmpty);
        List<Map<String, Object>> devices = new ArrayList<>();
        for (MappingDevice d : doc.getDevices()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("deviceCode", d.getDeviceCode());
            item.put("deviceName", d.getDeviceName());
            item.put("deviceIp", d.getDeviceIp());
            item.put("deviceType", d.getDeviceType());
            item.put("location", d.getLocation());
            item.put("brand", d.getBrand());
            item.put("model", d.getModel());
            item.put("collectType", "ping");
            item.put("collectPort", 0);
            item.put("warrantyExpire", d.getWarrantyExpire());
            devices.add(item);
        }
        body.put("devices", devices);
        try {
            return mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("构建设备清单同步报文失败", e);
        }
    }

    private void fail(int httpStatus, String body) {
        failCount++;
        int index = Math.min(failCount - 1, BACKOFF_SECONDS.length - 1);
        long next = System.currentTimeMillis() + BACKOFF_SECONDS[index] * 1000L;
        nextAttemptAt = next;
        runtimeState.markInventorySyncFailure(
                "HTTP " + httpStatus + " " + (body == null ? "" : body),
                LocalDateTime.now().plusSeconds(BACKOFF_SECONDS[index]).format(TS));
        log.warn("设备清单同步失败，{} 秒后重试: HTTP {} {}", BACKOFF_SECONDS[index], httpStatus, body);
    }

    private long intervalMs() {
        ConfigHolder.Local local = configHolder.getConfig().getLocal();
        int seconds = local == null ? 300 : local.getInventorySyncInterval();
        return Math.max(15, seconds) * 1000L;
    }
}
```

409 重试已限制为一次：`syncOnce(allowEmpty)` 调 `syncOnce(allowEmpty, 0)`，409 时最多再调一次；第二次仍为 409 则按失败处理，避免云端持续返回 409 时无限递归。

- [ ] **Step 5: RuntimeState / ConfigHolder / HeartbeatService**

`ConfigHolder.Local` 增加：

```java
/** 设备清单向云端同步周期（秒），默认 300 秒 */
private int inventorySyncInterval = 300;
```

`normalize` 增加：

```java
if (cfg.getLocal().getInventorySyncInterval() <= 0) {
    cfg.getLocal().setInventorySyncInterval(300);
}
```

`RuntimeState` 增加字段与访问器：

```java
private volatile String lastInventorySyncAt = "-";
private volatile String lastInventorySyncResult = "-";
private volatile String nextInventoryRetryAt = "-";
private volatile long inventorySyncFailCount = 0L;
private volatile String inventorySyncStatus = "pending";
private volatile long inventoryRevision = 0L;
private volatile int inventoryDeviceCount = 0;

public void markInventorySyncSuccess(long revision, int deviceCount) {
    lastInventorySyncAt = now();
    lastInventorySyncResult = "success";
    nextInventoryRetryAt = "-";
    inventorySyncFailCount = 0L;
    inventorySyncStatus = "success";
    inventoryRevision = revision;
    inventoryDeviceCount = deviceCount;
}

public void markInventorySyncFailure(String message, String nextRetryAt) {
    lastInventorySyncResult = message;
    nextInventoryRetryAt = nextRetryAt;
    inventorySyncFailCount++;
    inventorySyncStatus = "failed";
}

public void markInventorySyncBlocked(String message) {
    lastInventorySyncResult = message;
    nextInventoryRetryAt = "-";
    inventorySyncStatus = "empty_blocked";
}

public String getLastInventorySyncAt() { return lastInventorySyncAt; }
public String getLastInventorySyncResult() { return lastInventorySyncResult; }
public String getNextInventoryRetryAt() { return nextInventoryRetryAt; }
public long getInventorySyncFailCount() { return inventorySyncFailCount; }
public String getInventorySyncStatus() { return inventorySyncStatus; }
public long getInventoryRevision() { return inventoryRevision; }
public int getInventoryDeviceCount() { return inventoryDeviceCount; }
```

`HeartbeatService` 构造器增加 `LocalInventoryStore inventoryStore` 参数并保存字段：

```java
private final LocalInventoryStore inventoryStore;

public HeartbeatService(ConfigHolder configHolder, CloudClient cloudClient,
                        RuntimeState runtimeState, LocalInventoryStore inventoryStore) {
    this.configHolder = configHolder;
    this.cloudClient = cloudClient;
    this.runtimeState = runtimeState;
    this.inventoryStore = inventoryStore;
}
```

`heartbeat()` 内改为：

```java
LocalInventoryDocument doc = inventoryStore.read();
String body = "{\"gatewayCode\":\"" + cfg.getGatewayCode() + "\","
        + "\"status\":\"online\","
        + "\"inventoryRevision\":" + doc.getRevision() + ","
        + "\"inventoryDeviceCount\":" + doc.getDevices().size() + ","
        + "\"lastInventorySyncAt\":\"" + runtimeState.getLastInventorySyncAt() + "\","
        + "\"inventorySyncStatus\":\"" + runtimeState.getInventorySyncStatus() + "\","
        + "\"time\":\"" + LocalDateTime.now().format(FMT) + "\"}";
```

- [ ] **Step 6: 运行测试确认通过**

```powershell
$env:JAVA_HOME='D:\DevTools\jdk-21'
& 'D:\DevTools\apache-maven-3.9.6\bin\mvn.cmd' -f netsight-gateway-agent/pom.xml -Dtest=InventorySyncTest test
```

Expected: PASS。

- [ ] **Step 7: 提交**

```powershell
git add netsight-gateway-agent/src/main/java/com/netsight/gateway/connector/CloudClient.java `
  netsight-gateway-agent/src/main/java/com/netsight/gateway/sync/InventorySync.java `
  netsight-gateway-agent/src/main/java/com/netsight/gateway/core/RuntimeState.java `
  netsight-gateway-agent/src/main/java/com/netsight/gateway/core/ConfigHolder.java `
  netsight-gateway-agent/src/main/java/com/netsight/gateway/connector/HeartbeatService.java `
  netsight-gateway-agent/src/test/java/com/netsight/gateway/sync/InventorySyncTest.java
git commit -m "feat(gateway): 设备清单全量同步与退避重试"
```

---

### Task 5: 本地管理页设备增删改

**Files:**
- Modify: `netsight-gateway-agent/src/main/resources/static/index.html`
- Modify: `netsight-gateway-agent/src/main/resources/static/assets/app.js`

**Interfaces:**
- Consumes: `GET/POST/PUT/DELETE /local/api/devices`、`POST /local/api/devices/sync`、`GET /local/api/devices/sync-status`。
- Produces: 可在 `/local` 页面完成设备新增、编辑、删除、清空全部、立即同步。

- [ ] **Step 1: 替换设备页签 HTML**

把 `index.html` 中 `<!-- 设备采集 -->` 到对应 `</div>` 之间替换为：

```html
<!-- 设备管理 -->
<div class="page" id="page-devices" style="display:none">
  <div class="card">
    <h3>设备管理（本地清单权威源）</h3>
    <div style="margin-bottom:12px;display:flex;gap:8px;align-items:center;flex-wrap:wrap">
      <button class="btn primary" onclick="openDeviceDialog()">新增设备</button>
      <button class="btn" onclick="syncDevices(this)">立即同步</button>
      <button class="btn danger" onclick="clearAllDevices()">清空全部</button>
      <span id="devMeta" style="font-size:12px;color:#6b7280"></span>
    </div>
    <div id="syncResult" style="margin-bottom:10px;font-size:12px;color:#6b7280"></div>
    <table>
      <thead><tr><th>设备编码</th><th>名称</th><th>IP</th><th>类型</th><th>位置</th><th>状态</th><th>最近采集</th><th>操作</th></tr></thead>
      <tbody id="devTable"><tr><td colspan="8" class="empty">加载中…</td></tr></tbody>
    </table>
  </div>
</div>

<!-- 设备编辑对话框 -->
<div class="dialog-mask" id="devMask">
  <div class="dialog">
    <h3 id="devTitle">新增设备</h3>
    <div class="form-item"><label>设备编码（留空自动生成）</label><input id="devCode" maxlength="64"></div>
    <div class="form-item"><label>设备名称</label><input id="devName" maxlength="64"></div>
    <div class="form-item"><label>管理 IP</label><input id="devIp" placeholder="192.168.1.10"></div>
    <div class="form-item"><label>设备类型</label>
      <select id="devType" style="width:100%;height:40px;border:1px solid #e5e7eb;border-radius:8px;padding:0 12px">
        <option value="network">网络设备</option>
        <option value="camera">视频摄像头</option>
        <option value="nvr">NVR录像机</option>
        <option value="door_controller">门禁控制器</option>
      </select>
    </div>
    <div class="form-item"><label>部署位置</label><input id="devLocation" maxlength="256"></div>
    <div class="form-item"><label>品牌</label><input id="devBrand" maxlength="64"></div>
    <div class="form-item"><label>型号</label><input id="devModel" maxlength="128"></div>
    <div class="form-item"><label>保修截止（YYYY-MM-DD）</label><input id="devWarranty" placeholder="2028-12-31"></div>
    <div style="display:flex;gap:10px;justify-content:flex-end;margin-top:16px">
      <button class="btn" onclick="hideDeviceDialog()">取消</button>
      <button class="btn primary" id="devSaveBtn" onclick="saveDevice()">保存</button>
    </div>
  </div>
</div>
```

- [ ] **Step 2: 替换 app.js 设备逻辑**

把 `/* ===== 设备采集 ===== */` 到 `/* ===== 链路设置 ===== */` 之前替换为：

```javascript
/* ===== 设备管理 ===== */
var EDIT_DEVICE_CODE = null;

function esc(v) {
  return String(v == null ? '' : v).replace(/[&<>"']/g, function (c) {
    return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
  });
}

function loadDevices() {
  api('/local/api/devices').then(function (data) {
    if (data.code !== 200) { return handleAuthError(data); }
    var meta = data.data || {};
    var items = meta.items || [];
    document.getElementById('devMeta').textContent =
      'revision ' + (meta.revision || '-') + ' · 设备 ' + items.length + ' 台 · 同步 ' + (meta.syncStatus || '-') +
      ' · 最近 ' + (meta.lastSyncAt || '-');
    document.getElementById('syncResult').textContent = meta.nextRetryAt && meta.nextRetryAt !== '-'
      ? '下次重试：' + meta.nextRetryAt : '';
    var tb = document.getElementById('devTable');
    if (items.length === 0) {
      tb.innerHTML = '<tr><td colspan="8" class="empty">暂无设备，请点击“新增设备”录入</td></tr>';
      return;
    }
    tb.innerHTML = items.map(function (d) {
      var badge = '<span class="badge gray">未采集</span>';
      if (d.up === true && d.lineAbnormal === false) badge = '<span class="badge ok">在线</span>';
      else if (d.up === true && d.lineAbnormal === true) badge = '<span class="badge warn">链路异常</span>';
      else if (d.up === false) badge = '<span class="badge err">离线</span>';
      return '<tr><td>' + esc(d.deviceCode) + '</td><td>' + esc(d.deviceName || '-') + '</td><td>' +
        esc(d.deviceIp || '-') + '</td><td>' + esc(d.deviceType || '-') + '</td><td>' +
        esc(d.location || '-') + '</td><td>' + badge + '</td><td>' + esc(d.collectedAt || '-') + '</td>' +
        '<td><button class="btn" onclick="openDeviceDialog(\'' + esc(d.deviceCode) + '\')">编辑</button> ' +
        '<button class="btn danger" onclick="deleteDevice(\'' + esc(d.deviceCode) + '\',\'' + esc(d.deviceName || '') + '\')">删除</button></td></tr>';
    }).join('');
  }).catch(function (e) { toast('加载设备失败: ' + e.message, false); });
}

function openDeviceDialog(code) {
  EDIT_DEVICE_CODE = code || null;
  document.getElementById('devTitle').textContent = code ? '编辑设备' : '新增设备';
  document.getElementById('devCode').value = code || '';
  document.getElementById('devCode').disabled = !!code;
  ['devName', 'devIp', 'devLocation', 'devBrand', 'devModel', 'devWarranty'].forEach(function (id) {
    document.getElementById(id).value = '';
  });
  document.getElementById('devType').value = 'camera';
  if (code) {
    api('/local/api/devices').then(function (data) {
      var d = (data.data.items || []).filter(function (x) { return x.deviceCode === code; })[0];
      if (!d) { toast('设备不存在', false); return; }
      document.getElementById('devName').value = d.deviceName || '';
      document.getElementById('devIp').value = d.deviceIp || '';
      document.getElementById('devType').value = d.deviceType || 'camera';
      document.getElementById('devLocation').value = d.location || '';
      document.getElementById('devBrand').value = d.brand || '';
      document.getElementById('devModel').value = d.model || '';
      document.getElementById('devWarranty').value = d.warrantyExpire || '';
    });
  }
  document.getElementById('devMask').classList.add('show');
}

function hideDeviceDialog() {
  document.getElementById('devMask').classList.remove('show');
}

function saveDevice() {
  var payload = {
    deviceCode: document.getElementById('devCode').disabled ? EDIT_DEVICE_CODE : document.getElementById('devCode').value.trim(),
    deviceName: document.getElementById('devName').value.trim(),
    deviceIp: document.getElementById('devIp').value.trim(),
    deviceType: document.getElementById('devType').value,
    location: document.getElementById('devLocation').value.trim(),
    brand: document.getElementById('devBrand').value.trim(),
    model: document.getElementById('devModel').value.trim(),
    warrantyExpire: document.getElementById('devWarranty').value.trim() || null
  };
  var method = EDIT_DEVICE_CODE ? 'PUT' : 'POST';
  var path = EDIT_DEVICE_CODE ? '/local/api/devices/' + encodeURIComponent(EDIT_DEVICE_CODE) : '/local/api/devices';
  var btn = document.getElementById('devSaveBtn');
  btn.disabled = true;
  api(path, { method: method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) })
    .then(function (data) {
      if (data.code !== 200) { toast(data.msg || '保存失败', false); return; }
      toast(EDIT_DEVICE_CODE ? '设备已更新' : '设备已新增', true);
      hideDeviceDialog();
      loadDevices();
      loadOverview();
    })
    .catch(function (e) { toast('保存失败: ' + e.message, false); })
    .finally(function () { btn.disabled = false; });
}

function deleteDevice(code, name) {
  api('/local/api/devices').then(function (data) {
    var items = (data.data && data.data.items) || [];
    var isLast = items.length === 1;
    var msg = '确认删除设备【' + name + '】？';
    if (isLast) msg += '\n这是最后一台设备，删除后清单为空，需要二次确认。';
    if (!confirm(msg)) return;
    if (isLast && !confirm('再次确认：删除后将清空全部设备并同步到云端。')) return;
    api('/local/api/devices/' + encodeURIComponent(code) + '?confirmEmpty=' + isLast, { method: 'DELETE' })
      .then(function (res) {
        if (res.code !== 200) { toast(res.msg || '删除失败', false); return; }
        toast('设备已删除', true);
        loadDevices();
        loadOverview();
      }).catch(function (e) { toast('删除失败: ' + e.message, false); });
  });
}

function clearAllDevices() {
  var phrase = prompt('输入“清空全部”确认清空本地设备清单并同步云端：');
  if (phrase !== '清空全部') { toast('确认短语不正确', false); return; }
  api('/local/api/devices/clear', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ confirm: phrase })
  }).then(function (data) {
    if (data.code !== 200) { toast(data.msg || '清空失败', false); return; }
    toast('清单已清空', true);
    loadDevices();
    loadOverview();
  }).catch(function (e) { toast('清空失败: ' + e.message, false); });
}

function syncDevices(btn) {
  if (btn) btn.disabled = true;
  api('/local/api/devices/sync', { method: 'POST' }).then(function (data) {
    if (data.code !== 200) { handleAuthError(data); return; }
    toast('同步已触发', true);
    setTimeout(loadDevices, 2000);
    loadOverview();
  }).catch(function (e) { toast('同步失败: ' + e.message, false); })
    .finally(function () { if (btn) btn.disabled = false; });
}
```

把 `titles` 中的 `devices: '设备采集'` 改为 `devices: '设备管理'`；把 `if (page === 'devices') loadDevices();` 保持不变。删除旧的 `doSync()` 或改名为兼容 `/local/api/sync` 的“重新加载清单”函数，避免与 `syncDevices()` 重名。

- [ ] **Step 3: 手工验证**

启动网关本地服务后，在浏览器打开 `/local`：

1. 新增设备，检查列表出现新设备，`revision` 增加。
2. 刷新页面，设备仍在。
3. 编辑设备，检查名称/IP 更新。
4. 删除非最后一台设备成功；删除最后一台时出现两次确认。
5. 清空全部输入错误短语被拒绝，输入“清空全部”成功。
6. 检查 `mapping.json` 的 `revision/updatedAt` 和 `targets/ping.json` 的 target 数量。

- [ ] **Step 4: 提交**

```powershell
git add netsight-gateway-agent/src/main/resources/static/index.html `
  netsight-gateway-agent/src/main/resources/static/assets/app.js
git commit -m "feat(gateway): 本地管理页设备增删改"
```

---

### Task 6: 云端 DDL、实体与 Mapper

**Files:**
- Create: `netsight-server/sql/netsight-upgrade-v1.3.2-edge-inventory-sync.sql`
- Modify: `netsight-server/src/main/java/com/netsight/modules/system/entity/Device.java`
- Modify: `netsight-server/src/main/java/com/netsight/modules/system/entity/EdgeGateway.java`
- Modify: `netsight-server/src/main/java/com/netsight/modules/system/mapper/DeviceMapper.java`

**Interfaces:**
- Produces:
  - `Device.getSource()/getSyncedAt()`
  - `EdgeGateway.getInventoryRevision()/getInventorySyncedAt()/getInventoryDeviceCount()`
  - `DeviceMapper.selectByGatewayAndCodeIncludingDeleted(Long, String)`
  - `DeviceMapper.restoreById(Long)`

- [ ] **Step 1: 写迁移 SQL**

`netsight-server/sql/netsight-upgrade-v1.3.2-edge-inventory-sync.sql`：

```sql
-- NetSight v1.3.2：设备清单权威源下沉到边缘网关
ALTER TABLE device
  ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'cloud' COMMENT '来源：edge/cloud' AFTER collect_port,
  ADD COLUMN synced_at DATETIME NULL COMMENT '最后同步时间' AFTER source;

ALTER TABLE edge_gateway
  ADD COLUMN inventory_revision BIGINT NOT NULL DEFAULT 0 COMMENT '设备清单版本' AFTER status,
  ADD COLUMN inventory_synced_at DATETIME NULL COMMENT '设备清单最后同步时间' AFTER inventory_revision,
  ADD COLUMN inventory_device_count INT NOT NULL DEFAULT 0 COMMENT '设备清单数量' AFTER inventory_synced_at;

ALTER TABLE device DROP INDEX uk_device_code;
ALTER TABLE device ADD UNIQUE KEY uk_gateway_device (gateway_id, device_code);
```

执行前先在 115 测试库备份 `device`、`edge_gateway`：

```powershell
@'
import paramiko
c = paramiko.SSHClient(); c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect('115.159.194.149', 22, 'root', 'Chinaunicom@1358', timeout=10)
cmd = "mysqldump -uroot -p'Chinaunicom@1358' netsight device edge_gateway > /tmp/edge-inventory-before-20261124.sql"
i, o, e = c.exec_command(cmd)
print(o.read().decode(), e.read().decode())
c.close()
'@ | python -
```

Expected: `/tmp/edge-inventory-before-20261124.sql` 非空；`mysqldump` 的 tablespace 警告不影响备份文件生成。

- [ ] **Step 2: 写实体字段**

`Device.java` 增加：

```java
/** 来源：edge=边缘网关同步 / cloud=云端历史数据 */
private String source;

/** 最后同步时间 */
private java.time.LocalDateTime syncedAt;
```

`EdgeGateway.java` 增加：

```java
/** 设备清单最后成功应用的 revision */
private Long inventoryRevision;

/** 设备清单最后同步时间 */
private java.time.LocalDateTime inventorySyncedAt;

/** 设备清单数量 */
private Integer inventoryDeviceCount;
```

- [ ] **Step 3: 扩展 DeviceMapper**

```java
package com.netsight.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.netsight.modules.system.entity.Device;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface DeviceMapper extends BaseMapper<Device> {

    @Select("SELECT * FROM device WHERE gateway_id = #{gatewayId} AND device_code = #{deviceCode} LIMIT 1")
    Device selectByGatewayAndCodeIncludingDeleted(@Param("gatewayId") Long gatewayId,
                                                   @Param("deviceCode") String deviceCode);

    @Update("UPDATE device SET del_flag = 0, update_time = NOW() WHERE id = #{id}")
    int restoreById(@Param("id") Long id);
}
```

- [ ] **Step 4: 编译**

```powershell
$env:JAVA_HOME='D:\DevTools\jdk-21'
& 'D:\DevTools\apache-maven-3.9.6\bin\mvn.cmd' -f netsight-server/pom.xml -DskipTests compile
```

Expected: BUILD SUCCESS。

- [ ] **Step 5: 提交**

```powershell
git add netsight-server/sql/netsight-upgrade-v1.3.2-edge-inventory-sync.sql `
  netsight-server/src/main/java/com/netsight/modules/system/entity/Device.java `
  netsight-server/src/main/java/com/netsight/modules/system/entity/EdgeGateway.java `
  netsight-server/src/main/java/com/netsight/modules/system/mapper/DeviceMapper.java
git commit -m "feat(server): 设备清单同步字段与数据库迁移"
```

---

### Task 7: 云端同步接口与幂等 upsert

**Files:**
- Create: `netsight-server/src/main/java/com/netsight/modules/system/dto/DeviceInventorySyncRequest.java`
- Create: `netsight-server/src/main/java/com/netsight/modules/system/dto/DeviceInventorySyncResult.java`
- Create: `netsight-server/src/main/java/com/netsight/common/exception/StaleRevisionException.java`
- Create: `netsight-server/src/main/java/com/netsight/modules/system/service/DeviceInventorySyncService.java`
- Modify: `netsight-server/src/main/java/com/netsight/modules/system/controller/EdgeConfigController.java`
- Test: `netsight-server/src/test/java/com/netsight/modules/system/service/DeviceInventorySyncServiceTest.java`

**Interfaces:**
- Consumes: `EdgeGatewayService.getByToken`、`DeviceMapper`、`EdgeGatewayMapper`。
- Produces:
  - `DeviceInventorySyncService.sync(EdgeGateway gateway, DeviceInventorySyncRequest req): DeviceInventorySyncResult`
  - `POST /edge/config/devices/sync`

- [ ] **Step 1: 写失败测试**

```java
package com.netsight.modules.system.service;

import com.netsight.common.exception.ServiceException;
import com.netsight.common.exception.StaleRevisionException;
import com.netsight.modules.system.dto.DeviceInventorySyncRequest;
import com.netsight.modules.system.entity.Device;
import com.netsight.modules.system.entity.EdgeGateway;
import com.netsight.modules.system.mapper.DeviceMapper;
import com.netsight.modules.system.mapper.EdgeGatewayMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DeviceInventorySyncServiceTest {

    private final DeviceMapper deviceMapper = mock(DeviceMapper.class);
    private final EdgeGatewayMapper gatewayMapper = mock(EdgeGatewayMapper.class);
    private final DeviceInventorySyncService service = new DeviceInventorySyncService(gatewayMapper, deviceMapper);

    private EdgeGateway gateway(long revision) {
        EdgeGateway g = new EdgeGateway();
        g.setId(14L);
        g.setTenantId(34L);
        g.setGatewayCode("GW19920261122192456");
        g.setInventoryRevision(revision);
        return g;
    }

    private DeviceInventorySyncRequest request(long revision, String... codes) {
        DeviceInventorySyncRequest req = new DeviceInventorySyncRequest();
        req.setGatewayCode("GW19920261122192456");
        req.setRevision(revision);
        req.setFull(true);
        req.setAllowEmpty(false);
        for (String code : codes) {
            DeviceInventorySyncRequest.Item item = new DeviceInventorySyncRequest.Item();
            item.setDeviceCode(code);
            item.setDeviceName("设备-" + code);
            item.setDeviceIp("10.0.0.1");
            item.setDeviceType("camera");
            req.getDevices().add(item);
        }
        return req;
    }

    @Test
    void rejectsStaleRevision() {
        StaleRevisionException ex = assertThrows(StaleRevisionException.class,
                () -> service.sync(gateway(5), request(4, "DEV-1")));
        assertEquals(5, ex.getCurrentRevision());
    }

    @Test
    void rejectsEmptyListWithoutAllowEmpty() {
        DeviceInventorySyncRequest req = request(6);
        assertThrows(ServiceException.class, () -> service.sync(gateway(5), req));
    }

    @Test
    void insertsNewDeviceWithEdgeSource() {
        when(deviceMapper.selectByGatewayAndCodeIncludingDeleted(14L, "DEV-1")).thenReturn(null);

        DeviceInventorySyncRequest req = request(6, "DEV-1");
        service.sync(gateway(5), req);

        verify(deviceMapper).insert(argThat(d ->
                "DEV-1".equals(d.getDeviceCode())
                        && "edge".equals(d.getSource())
                        && Long.valueOf(34L).equals(d.getTenantId())
                        && Long.valueOf(14L).equals(d.getGatewayId())));
        verify(gatewayMapper).updateById(argThat(g -> Long.valueOf(6L).equals(g.getInventoryRevision())));
    }

    @Test
    void softDeletesDeviceMissingFromFullList() {
        Device old = new Device();
        old.setId(100L);
        old.setDeviceCode("DEV-OLD");
        old.setGatewayId(14L);
        when(deviceMapper.selectList(any())).thenReturn(List.of(old));
        when(deviceMapper.selectByGatewayAndCodeIncludingDeleted(14L, "DEV-1")).thenReturn(null);

        service.sync(gateway(5), request(6, "DEV-1"));

        verify(deviceMapper).deleteById(100L);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```powershell
$env:JAVA_HOME='D:\DevTools\jdk-21'
& 'D:\DevTools\apache-maven-3.9.6\bin\mvn.cmd' -f netsight-server/pom.xml -Dtest=DeviceInventorySyncServiceTest test
```

Expected: FAIL，类不存在。

- [ ] **Step 3: 写 DTO 与异常**

`DeviceInventorySyncRequest.java`：

```java
package com.netsight.modules.system.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class DeviceInventorySyncRequest {
    private String gatewayCode;
    private Long revision;
    private String updatedAt;
    private Boolean full = true;
    private Boolean allowEmpty = false;
    private List<Item> devices = new ArrayList<>();

    @Data
    public static class Item {
        private String deviceCode;
        private String deviceName;
        private String deviceIp;
        private String deviceType;
        private String location;
        private String brand;
        private String model;
        private String collectType;
        private Integer collectPort;
        private String warrantyExpire;
    }
}
```

`DeviceInventorySyncResult.java`：

```java
package com.netsight.modules.system.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class DeviceInventorySyncResult {
    private long revision;
    private int created;
    private int updated;
    private int disabled;
    private int unchanged;
    private String syncTime;
}
```

`StaleRevisionException.java`：

```java
package com.netsight.common.exception;

import lombok.Getter;

@Getter
public class StaleRevisionException extends RuntimeException {
    private final long currentRevision;

    public StaleRevisionException(long currentRevision) {
        super("STALE_REVISION");
        this.currentRevision = currentRevision;
    }
}
```

- [ ] **Step 4: 写同步服务**

```java
package com.netsight.modules.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.netsight.common.exception.ServiceException;
import com.netsight.common.exception.StaleRevisionException;
import com.netsight.modules.system.dto.DeviceInventorySyncRequest;
import com.netsight.modules.system.dto.DeviceInventorySyncResult;
import com.netsight.modules.system.entity.Device;
import com.netsight.modules.system.entity.EdgeGateway;
import com.netsight.modules.system.mapper.DeviceMapper;
import com.netsight.modules.system.mapper.EdgeGatewayMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceInventorySyncService {

    private static final String IPV4 = "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$";
    private static final Set<String> TYPES = Set.of("network", "camera", "nvr", "door_controller");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final EdgeGatewayMapper edgeGatewayMapper;
    private final DeviceMapper deviceMapper;

    @Transactional(rollbackFor = Exception.class)
    public DeviceInventorySyncResult sync(EdgeGateway gateway, DeviceInventorySyncRequest req) {
        if (req == null || req.getRevision() == null) {
            throw new ServiceException(400, "revision 不能为空");
        }
        if (!Objects.equals(gateway.getGatewayCode(), req.getGatewayCode())) {
            throw new ServiceException(403, "gatewayCode 与 Token 不匹配");
        }
        long current = gateway.getInventoryRevision() == null ? 0L : gateway.getInventoryRevision();
        if (req.getRevision() < current) {
            throw new StaleRevisionException(current);
        }
        List<DeviceInventorySyncRequest.Item> items = req.getDevices() == null ? List.of() : req.getDevices();
        if (items.isEmpty() && !Boolean.TRUE.equals(req.getAllowEmpty())) {
            throw new ServiceException(400, "空设备清单被拒绝；如确需清空，请在本地页面二次确认");
        }
        Set<String> codes = validateAndCollect(items);

        int created = 0;
        int updated = 0;
        int unchanged = 0;
        LocalDateTime now = LocalDateTime.now();

        for (DeviceInventorySyncRequest.Item item : items) {
            Device existing = deviceMapper.selectByGatewayAndCodeIncludingDeleted(gateway.getId(), item.getDeviceCode());
            if (existing == null) {
                deviceMapper.insert(toNewDevice(gateway, item, now));
                created++;
                continue;
            }
            boolean restored = existing.getDelFlag() != null && existing.getDelFlag() == 2;
            if (restored) {
                deviceMapper.restoreById(existing.getId());
            }
            Device update = toUpdateDevice(gateway, existing.getId(), item, now);
            if (restored || !sameConfig(existing, update)) {
                deviceMapper.updateById(update);
                updated++;
            } else {
                unchanged++;
            }
        }

        int disabled = 0;
        if (Boolean.TRUE.equals(req.getFull())) {
            List<Device> active = deviceMapper.selectList(new LambdaQueryWrapper<Device>()
                    .eq(Device::getGatewayId, gateway.getId()));
            for (Device d : active) {
                if (!codes.contains(d.getDeviceCode())) {
                    deviceMapper.deleteById(d.getId());
                    disabled++;
                }
            }
        }

        EdgeGateway updateGateway = new EdgeGateway();
        updateGateway.setId(gateway.getId());
        updateGateway.setInventoryRevision(req.getRevision());
        updateGateway.setInventorySyncedAt(now);
        updateGateway.setInventoryDeviceCount(items.size());
        edgeGatewayMapper.updateById(updateGateway);

        String syncTime = now.format(TS);
        log.info("网关[{}]设备清单同步完成: revision={}, created={}, updated={}, disabled={}, unchanged={}",
                gateway.getGatewayCode(), req.getRevision(), created, updated, disabled, unchanged);
        return new DeviceInventorySyncResult(req.getRevision(), created, updated, disabled, unchanged, syncTime);
    }

    private Set<String> validateAndCollect(List<DeviceInventorySyncRequest.Item> items) {
        Set<String> codes = new HashSet<>();
        for (DeviceInventorySyncRequest.Item item : items) {
            if (!StringUtils.hasText(item.getDeviceCode()) || item.getDeviceCode().length() > 64
                    || !item.getDeviceCode().matches("[A-Za-z0-9_-]+")) {
                throw new ServiceException(400, "deviceCode 不合法: " + item.getDeviceCode());
            }
            if (!codes.add(item.getDeviceCode())) {
                throw new ServiceException(400, "deviceCode 重复: " + item.getDeviceCode());
            }
            if (!StringUtils.hasText(item.getDeviceName())) {
                throw new ServiceException(400, "deviceName 不能为空: " + item.getDeviceCode());
            }
            if (!StringUtils.hasText(item.getDeviceIp()) || !item.getDeviceIp().trim().matches(IPV4)) {
                throw new ServiceException(400, "deviceIp 不是合法 IPv4: " + item.getDeviceCode());
            }
            if (item.getDeviceType() == null || !TYPES.contains(item.getDeviceType())) {
                throw new ServiceException(400, "deviceType 不合法: " + item.getDeviceCode());
            }
            if (StringUtils.hasText(item.getWarrantyExpire())) {
                try {
                    LocalDate.parse(item.getWarrantyExpire().trim());
                } catch (Exception e) {
                    throw new ServiceException(400, "warrantyExpire 格式错误: " + item.getDeviceCode());
                }
            }
        }
        return codes;
    }

    private Device toNewDevice(EdgeGateway gateway, DeviceInventorySyncRequest.Item item, LocalDateTime now) {
        Device d = new Device();
        fill(d, gateway, item, now);
        return d;
    }

    private Device toUpdateDevice(EdgeGateway gateway, Long id, DeviceInventorySyncRequest.Item item, LocalDateTime now) {
        Device d = new Device();
        d.setId(id);
        fill(d, gateway, item, now);
        return d;
    }

    private void fill(Device d, EdgeGateway gateway, DeviceInventorySyncRequest.Item item, LocalDateTime now) {
        d.setDeviceCode(item.getDeviceCode());
        d.setDeviceName(item.getDeviceName().trim());
        d.setDeviceType(item.getDeviceType());
        d.setBrand(trim(item.getBrand()));
        d.setModel(trim(item.getModel()));
        d.setIpAddress(item.getDeviceIp().trim());
        d.setLocation(trim(item.getLocation()));
        d.setGatewayId(gateway.getId());
        d.setTenantId(gateway.getTenantId());
        d.setCollectType("ping");
        d.setCollectPort(0);
        d.setWarrantyExpire(StringUtils.hasText(item.getWarrantyExpire())
                ? LocalDate.parse(item.getWarrantyExpire().trim()) : null);
        d.setSource("edge");
        d.setSyncedAt(now);
    }

    private boolean sameConfig(Device a, Device b) {
        return Objects.equals(a.getDeviceName(), b.getDeviceName())
                && Objects.equals(a.getDeviceType(), b.getDeviceType())
                && Objects.equals(a.getBrand(), b.getBrand())
                && Objects.equals(a.getModel(), b.getModel())
                && Objects.equals(a.getIpAddress(), b.getIpAddress())
                && Objects.equals(a.getLocation(), b.getLocation())
                && Objects.equals(a.getWarrantyExpire(), b.getWarrantyExpire())
                && Objects.equals(a.getCollectType(), b.getCollectType())
                && Objects.equals(a.getCollectPort(), b.getCollectPort())
                && Objects.equals(a.getSource(), b.getSource());
    }

    private String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
```

`DeviceInventorySyncService` 不保留只读提示常量；只读提示统一放在 `DeviceService.rejectCloudWrite()`。

- [ ] **Step 5: 扩展 EdgeConfigController**

在 `EdgeConfigController` 注入 `DeviceInventorySyncService`，增加以下 import 和方法：

```java
import com.netsight.common.exception.StaleRevisionException;
import com.netsight.modules.system.dto.DeviceInventorySyncRequest;
import com.netsight.modules.system.dto.DeviceInventorySyncResult;
import com.netsight.modules.system.service.DeviceInventorySyncService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
```

```java
@PostMapping("/devices/sync")
public R<Map<String, Object>> syncDevices(@RequestHeader("X-Gateway-Token") String token,
                                          @RequestBody DeviceInventorySyncRequest req) {
    EdgeGateway gateway = edgeGatewayService.getByToken(token);
    if (gateway == null) {
        throw new ServiceException(ResultCode.GATEWAY_TOKEN_INVALID);
    }
    try {
        DeviceInventorySyncResult result = deviceInventorySyncService.sync(gateway, req);
        Map<String, Object> data = new HashMap<>();
        data.put("revision", result.getRevision());
        data.put("created", result.getCreated());
        data.put("updated", result.getUpdated());
        data.put("disabled", result.getDisabled());
        data.put("unchanged", result.getUnchanged());
        data.put("syncTime", result.getSyncTime());
        return R.ok(data);
    } catch (StaleRevisionException e) {
        R<Map<String, Object>> r = R.fail(409, "STALE_REVISION");
        r.setData(Map.of("currentRevision", e.getCurrentRevision()));
        return r;
    }
}
```

- [ ] **Step 6: 运行测试确认通过**

```powershell
$env:JAVA_HOME='D:\DevTools\jdk-21'
& 'D:\DevTools\apache-maven-3.9.6\bin\mvn.cmd' -f netsight-server/pom.xml -Dtest=DeviceInventorySyncServiceTest test
```

Expected: PASS。

- [ ] **Step 7: 提交**

```powershell
git add netsight-server/src/main/java/com/netsight/modules/system/dto/DeviceInventorySyncRequest.java `
  netsight-server/src/main/java/com/netsight/modules/system/dto/DeviceInventorySyncResult.java `
  netsight-server/src/main/java/com/netsight/common/exception/StaleRevisionException.java `
  netsight-server/src/main/java/com/netsight/modules/system/service/DeviceInventorySyncService.java `
  netsight-server/src/main/java/com/netsight/modules/system/controller/EdgeConfigController.java `
  netsight-server/src/test/java/com/netsight/modules/system/service/DeviceInventorySyncServiceTest.java
git commit -m "feat(server): 边缘网关设备清单同步接口"
```

---

### Task 8: 云端设备管理只读

**Files:**
- Modify: `netsight-server/src/main/java/com/netsight/modules/system/service/DeviceService.java`
- Modify: `netsight-server/src/main/java/com/netsight/modules/system/controller/DeviceController.java`
- Modify: `netsight-ui/src/views/device/index.vue`
- Test: `netsight-server/src/test/java/com/netsight/modules/system/service/DeviceServiceReadOnlyTest.java`

**Interfaces:**
- Consumes: `DeviceService.addDevice/updateDevice/deleteDevice`。
- Produces: 三个写方法统一抛出 `ServiceException(403, "设备清单由边缘网关维护，请在网关本地管理页面操作")`；前端不再展示写入口。

- [ ] **Step 1: 写失败测试**

```java
package com.netsight.modules.system.service;

import com.netsight.common.exception.ServiceException;
import com.netsight.modules.system.entity.Device;
import com.netsight.modules.system.mapper.DeviceMapper;
import com.netsight.modules.system.mapper.DeviceTopologyMapper;
import com.netsight.modules.system.mapper.EdgeGatewayMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class DeviceServiceReadOnlyTest {

    private final DeviceService service = new DeviceService(
            mock(DeviceMapper.class), mock(EdgeGatewayMapper.class), mock(DeviceTopologyMapper.class));

    @Test
    void addIsRejected() {
        ServiceException ex = assertThrows(ServiceException.class, () -> service.addDevice(new Device()));
        assertEquals(403, ex.getCode());
    }

    @Test
    void updateIsRejected() {
        Device d = new Device();
        d.setId(1L);
        ServiceException ex = assertThrows(ServiceException.class, () -> service.updateDevice(d));
        assertEquals(403, ex.getCode());
    }

    @Test
    void deleteIsRejected() {
        ServiceException ex = assertThrows(ServiceException.class, () -> service.deleteDevice(1L));
        assertEquals(403, ex.getCode());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```powershell
$env:JAVA_HOME='D:\DevTools\jdk-21'
& 'D:\DevTools\apache-maven-3.9.6\bin\mvn.cmd' -f netsight-server/pom.xml -Dtest=DeviceServiceReadOnlyTest test
```

Expected: FAIL，当前会访问 mapper 或抛空指针，不是 403。

- [ ] **Step 3: 在 DeviceService 三个写方法首行加守卫**

```java
private static final String READONLY_MESSAGE = "设备清单由边缘网关维护，请在网关本地管理页面操作";

private void rejectCloudWrite() {
    throw new ServiceException(403, READONLY_MESSAGE);
}
```

在 `addDevice`、`updateDevice`、`deleteDevice` 的第一行调用：

```java
rejectCloudWrite();
```

保留原方法体，便于后续恢复或内部迁移；当前所有调用都会先被拒绝。

- [ ] **Step 4: 修改 Vue 设备页**

`netsight-ui/src/views/device/index.vue`：

1. 删除模板第 32-38 行“新增设备”按钮区。
2. 删除第 74-80 行操作列中的“修改”“删除”按钮，只保留“二维码”。
3. 删除第 91-178 行新增/修改弹窗。
4. 删除 `import { listDevice, addDevice, updateDevice, delDevice, getTopologyTree, getQrcodeInfo }` 中的 `addDevice/updateDevice/delDevice`。
5. 删除 `handleAdd()`、`submitForm()`、`cancel()`、`reset()`、`handleUpdate(row)`、`handleDelete(row)`、`oldWarranty`、`form`、`rules`、`title`、`open` 等仅服务于写弹窗的字段和方法。
6. 在表格中增加两列：

```html
<el-table-column label="来源" width="90" align="center">
  <template slot-scope="scope">{{ scope.row.source === 'edge' ? '边缘网关' : '云端' }}</template>
</el-table-column>
<el-table-column label="最后同步" prop="syncedAt" width="150" align="center"/>
```

7. 在表格上方保留一行只读提示：

```html
<el-alert title="设备清单由边缘网关维护，请在网关本地管理页面新增、修改或删除设备。" type="info" :closable="false" show-icon />
```

- [ ] **Step 5: 运行测试确认通过**

```powershell
$env:JAVA_HOME='D:\DevTools\jdk-21'
& 'D:\DevTools\apache-maven-3.9.6\bin\mvn.cmd' -f netsight-server/pom.xml -Dtest=DeviceServiceReadOnlyTest test
```

Expected: PASS。

- [ ] **Step 6: 前端编译**

```powershell
cd netsight-ui
npm run build:prod
```

Expected: `DONE Build complete`，无新增错误。

- [ ] **Step 7: 提交**

```powershell
git add netsight-server/src/main/java/com/netsight/modules/system/service/DeviceService.java `
  netsight-server/src/main/java/com/netsight/modules/system/controller/DeviceController.java `
  netsight-server/src/test/java/com/netsight/modules/system/service/DeviceServiceReadOnlyTest.java `
  netsight-ui/src/views/device/index.vue
git commit -m "feat(server): 云端设备管理改为只读"
```

---

### Task 9: 构建、迁移、部署与端到端验证

**Files:**
- 无新增文件；部署使用已构建的 `netsight-gateway-agent/target/nams-agent.jar`、`netsight-server/target/netsight-server.jar`、`netsight-ui/dist`。

**Interfaces:**
- Consumes: 前 8 个任务的代码与 SQL。
- Produces: `.199` 网关和 `115` 云端运行新版本，端到端验证通过。

- [ ] **Step 1: 本地全量测试与构建**

```powershell
$env:JAVA_HOME='D:\DevTools\jdk-21'
& 'D:\DevTools\apache-maven-3.9.6\bin\mvn.cmd' -f netsight-gateway-agent/pom.xml clean test
& 'D:\DevTools\apache-maven-3.9.6\bin\mvn.cmd' -f netsight-server/pom.xml clean test
& 'D:\DevTools\apache-maven-3.9.6\bin\mvn.cmd' -f netsight-gateway-agent/pom.xml package -DskipTests
& 'D:\DevTools\apache-maven-3.9.6\bin\mvn.cmd' -f netsight-server/pom.xml package -DskipTests
```

Expected: 两个模块 `BUILD SUCCESS`；`netsight-gateway-agent/target/nams-agent.jar`、`netsight-server/target/netsight-server.jar` 存在。

- [ ] **Step 2: 115 执行数据库迁移**

先备份，再执行：

```powershell
@'
import paramiko
c = paramiko.SSHClient(); c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect('115.159.194.149', 22, 'root', 'Chinaunicom@1358', timeout=10)
sftp = c.open_sftp()
sftp.put(r'D:\gitee\worktrees\NetSight1.0-wechat-mp-full-support\netsight-server\sql\netsight-upgrade-v1.3.2-edge-inventory-sync.sql',
         '/tmp/netsight-upgrade-v1.3.2-edge-inventory-sync.sql')
sftp.close()
cmd = "mysql -uroot -p'Chinaunicom@1358' netsight < /tmp/netsight-upgrade-v1.3.2-edge-inventory-sync.sql"
i, o, e = c.exec_command(cmd)
print(o.read().decode(), e.read().decode())
c.close()
'@ | python -
```

Expected: 无 `ERROR`；然后查询：

```sql
SHOW COLUMNS FROM device LIKE 'source';
SHOW COLUMNS FROM edge_gateway LIKE 'inventory_revision';
SHOW INDEX FROM device WHERE Key_name = 'uk_gateway_device';
```

Expected: 三个对象都存在。

- [ ] **Step 3: 部署 115 云端**

```powershell
@'
import paramiko, os, time
host, user, pwd = '115.159.194.149', 'root', 'Chinaunicom@1358'
local_jar = r'D:\gitee\worktrees\NetSight1.0-wechat-mp-full-support\netsight-server\target\netsight-server.jar'
c = paramiko.SSHClient(); c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect(host, 22, user, pwd, timeout=10)
ts = time.strftime('%Y%m%d%H%M%S')
c.exec_command(f'cp /opt/nams-server/netsight-server.jar /opt/nams-server/backups/netsight-server.jar.bak-{ts}')
sftp = c.open_sftp(); sftp.put(local_jar, '/opt/nams-server/netsight-server.jar'); sftp.close()
for cmd in ['kill $(cat /opt/nams-server/app.pid) 2>/dev/null || true',
            'sleep 2',
            'cd /opt/nams-server && bash start.sh']:
    i, o, e = c.exec_command(cmd)
    print(cmd, o.read().decode(), e.read().decode())
c.close()
'@ | python -
```

Expected: `start.sh` 输出启动成功；`app.pid` 更新；健康检查 `curl http://127.0.0.1:8080/actuator/health` 返回 `UP`。

- [ ] **Step 4: 部署 .199 网关**

`.199` 仅从 `WIN-20250831DNP`（env `client:582478:1417714677847867`）可达；先切换环境，再执行：

```powershell
@'
import paramiko, time
host, user, pwd = '192.168.1.199', 'root', 'Chinaunicom@1358'
local_jar = r'D:\gitee\worktrees\NetSight1.0-wechat-mp-full-support\netsight-gateway-agent\target\nams-agent.jar'
c = paramiko.SSHClient(); c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect(host, 22, user, pwd, timeout=10)
ts = time.strftime('%Y%m%d%H%M%S')
c.exec_command(f'cp /opt/nams-gateway/bin/nams-agent.jar /opt/nams-gateway/backups/nams-agent.jar.bak-{ts}')
sftp = c.open_sftp(); sftp.put(local_jar, '/opt/nams-gateway/bin/nams-agent.jar'); sftp.close()
for cmd in ['systemctl restart nams-agent', 'sleep 5', 'systemctl is-active nams-agent']:
    i, o, e = c.exec_command(cmd)
    print(cmd, o.read().decode(), e.read().decode())
c.close()
'@ | python -
```

Expected: `systemctl is-active` 输出 `active`；`/opt/nams-gateway/conf/targets/ping.json` 仍为 84 条（或本地真实设备数）。

- [ ] **Step 5: 端到端验证**

1. 打开 `http://192.168.1.199:8081/local`，新增一台测试设备，记录返回的 `deviceCode`。
2. 在 `.199` 检查：

```bash
python3 -c "import json; d=json.load(open('/opt/nams-gateway/conf/mapping.json')); print(d['revision'], len(d['devices']))"
python3 -c "import json; d=json.load(open('/opt/nams-gateway/conf/targets/ping.json')); print(len(d))"
```

Expected: `revision` 增加 1；`ping.json` 条数等于设备数。

3. 等待 5 分钟或点击“立即同步”，在 115 查询：

```sql
SELECT device_code, device_name, ip_address, gateway_id, tenant_id, source, synced_at, del_flag
FROM device WHERE gateway_id = 14 ORDER BY id DESC LIMIT 5;
SELECT inventory_revision, inventory_synced_at, inventory_device_count
FROM edge_gateway WHERE id = 14;
```

Expected: 新设备 `source='edge'`、`del_flag=0`、`synced_at` 非空；网关 `inventory_revision` 与本地 `revision` 一致。

4. 登录云端设备页，确认“新增设备”按钮、操作列“修改/删除”都不存在；列表能看到新设备及“边缘网关”来源。
5. 后端写接口拒绝已由 `DeviceServiceReadOnlyTest` 覆盖（403）；页面侧只需确认入口不存在。
6. 删除本地测试设备，等待同步后确认云端 `del_flag=2`；再重新新增同编码设备，确认云端恢复 `del_flag=0`。

- [ ] **Step 6: 记录与提交**

更新 `AGENTS.md` 的部署记录：目标 IP、部署时间、commit、SQL 文件名、验证结果、回滚方式（115 备份 jar + `.199` 备份 jar）。

```powershell
git add AGENTS.md
git commit -m "docs: 记录设备清单同步部署与验证结果"
```

---

## Self-Review

**1. Spec coverage**

- 本地清单文件、revision/updatedAt、原子写、备份：Task 1。
- `MappingSync` 只读本地、生成 `ping.json`：Task 2。
- 本地 CRUD、编码生成、IPv4/类型/保修校验、最后一台确认：Task 3。
- 5 分钟同步、立即触发、退避 15/30/60/120/300、409 重试、空清单保护：Task 4。
- 本地管理页新增/编辑/删除/清空/立即同步：Task 5。
- `device.source/synced_at`、`edge_gateway.inventory_*`、索引调整：Task 6。
- 云端 `POST /edge/config/devices/sync`、Token 推导租户、幂等 upsert、全量软删除、恢复：Task 7。
- 云端只读、前端移除写入口、来源/同步时间列：Task 8。
- 构建、迁移、部署、端到端验证：Task 9。

**2. Placeholder scan**

已检查无 `TBD/TODO/待定/实现细节`；所有代码步骤均给出具体类、方法、SQL 或命令。

**3. Type consistency**

- `MappingDevice` 统一由 `com.netsight.gateway.sync.MappingDevice` 提供；`SnapshotCollector`、`LocalDeviceService`、`InventorySync` 使用同一类型。
- `LocalInventoryStore.mutate` 使用 `UnaryOperator<LocalInventoryDocument>`，调用方返回 `null` 或设备对象。
- `CloudClient.SyncResponse` 为 record，`InventorySync` 使用 `httpStatus()/body()`。
- 云端 `DeviceInventorySyncRequest.Item` 字段与网关 `InventorySync.buildBody` 的 key 完全一致。
- 云端 `del_flag` 固定 `2`，与 `application.yml` 的 `logic-delete-value: 2` 一致。

**4. Known limitations**

- `InventorySync` 的 409 重试必须限制为一次；执行时按 Task 4 的说明增加 `retryCount` 参数，避免递归死循环。
- `.199` 只能从 WIN 环境访问；部署前必须切换环境，不能在 CUPC 环境直接连 `.199`。
- 现有 84 台设备 IP 仍只有 `192.168.1.55` 和 `192.168.1.99` 两个值，端到端验证只能证明链路通，不能证明真实设备在线；真实 IP 清单由租户在本地页面修正。
- 云端首次同步会把网关下已有的 `source='cloud'` 设备改为 `source='edge'`；如果本地清单缺少某台历史设备，该设备会被软删除，执行前需确认本地清单完整。
