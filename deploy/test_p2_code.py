"""P2 代码质量自动检查"""
import os, re, glob

SERVER = r"E:\gitee\NetSight1.0\netsight-server\src\main\java\com\netsight"
results = []

def test(name, ok, detail=""):
    results.append((name, ok, detail))
    print(f"{'PASS' if ok else 'FAIL'} | {name} | {detail}")

# 1. 检查@Slf4j使用
java_files = glob.glob(f"{SERVER}/**/*.java", recursive=True)
total_services = 0
with_slf4j = 0
for f in java_files:
    with open(f, encoding='utf-8') as fh:
        content = fh.read()
    if '/service/' in f and content.count('class ') > 0:
        total_services += 1
        if '@Slf4j' in content or 'LoggerFactory' in content:
            with_slf4j += 1
test("Service类@Slf4j注解", total_services > 0 and with_slf4j >= total_services * 0.8,
     f"{with_slf4j}/{total_services} service类有日志")

# 2. 检查切面类存在
aspect_files = [f for f in java_files if 'aspect' in f.lower() or 'Aspect' in f]
test("切面类存在", len(aspect_files) >= 3, f"切面类: {[os.path.basename(f) for f in aspect_files]}")

# 3. 检查R统一返回
r_files = [f for f in java_files if os.path.basename(f) == 'R.java']
test("统一返回R类", len(r_files) >= 1, f"R.java: {r_files[0] if r_files else 'NOT FOUND'}")

# 4. 检查实体类tenantId
entity_files = [f for f in java_files if '/entity/' in f.replace('\\','/') or '/domain/' in f.replace('\\','/')]
tenant_entities = 0
for f in entity_files:
    with open(f, encoding='utf-8') as fh:
        content = fh.read()
    if 'tenantId' in content or 'tenant_id' in content:
        tenant_entities += 1
test("多租户实体tenantId", tenant_entities >= 5, f"{tenant_entities}/{len(entity_files)}实体含tenantId")

# 5. 检查@Transactional
tx_count = sum(1 for f in java_files if open(f, encoding='utf-8').read().count('@Transactional') > 0)
test("事务注解使用", tx_count >= 3, f"{tx_count}个类使用@Transactional")

# 6. 检查@PreAuthorize
auth_count = sum(1 for f in java_files if open(f, encoding='utf-8').read().count('@PreAuthorize') > 0)
test("权限注解使用", auth_count >= 5, f"{auth_count}个Controller使用@PreAuthorize")

# 7. 检查logback配置
logback_path = r"E:\gitee\NetSight1.0\netsight-server\src\main\resources\logback-spring.xml"
test("logback配置文件", os.path.exists(logback_path), f"path={logback_path}")

# 8. 检查敏感配置硬编码
hardcoded = 0
for f in java_files:
    with open(f, encoding='utf-8') as fh:
        content = fh.read()
    if re.search(r'password\s*=\s*"[^"]+"', content) and 'test' not in f.lower():
        hardcoded += 1
test("无硬编码密码", hardcoded == 0, f"硬编码密码文件数={hardcoded}")

# 9. 检查MP多租户配置
mp_config = [f for f in java_files if 'MybatisPlus' in f or 'TenantLine' in f or 'MybatisConfig' in f]
test("MyBatisPlus多租户配置", len(mp_config) >= 1, f"配置类: {[os.path.basename(f) for f in mp_config]}")

# 10. 检查application.yml
yml_path = r"E:\gitee\NetSight1.0\netsight-server\src\main\resources\application.yml"
test("应用配置文件", os.path.exists(yml_path), "")

passed = sum(1 for _,ok,_ in results if ok)
failed = sum(1 for _,ok,_ in results if not ok)
print(f"\n=== P2 代码质量: {passed} PASS / {failed} FAIL / 共 {len(results)} 项 ===")
for name, ok, detail in results:
    if not ok: print(f"  FAIL: {name}: {detail}")
