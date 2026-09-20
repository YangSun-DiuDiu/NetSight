package com.netsight.common.util;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;

/**
 * Token 存量明文迁移（启动时一次性执行）
 *
 * 背景：历史版本 gateway_token / webhook_token / pushplus_token 均明文落库，
 * 本轮引入 TokenCrypto 后需把存量数据转为密文（enc: 前缀），保证：
 * 1. 鉴权路径（按密文查询）能匹配到存量行；
 * 2. 查看接口（decrypt）能还原明文展示。
 *
 * 幂等性：仅迁移「非 enc: 前缀」的值，重复启动不重复处理。
 * 表名/列名为固定常量（无用户输入），不存在 SQL 注入。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenCryptoMigration {

    private final JdbcTemplate jdbcTemplate;
    private final TokenCrypto tokenCrypto;

    @PostConstruct
    public void migrate() {
        migrateColumn("edge_gateway", "gateway_token");
        migrateColumn("edge_gateway", "pushplus_token");
        migrateColumn("sys_tenant", "webhook_token");
        migrateColumn("sys_tenant", "pushplus_token");
    }

    private void migrateColumn(String table, String column) {
        List<Map<String, Object>> rows;
        try {
            rows = jdbcTemplate.queryForList(
                    "SELECT id, " + column + " FROM " + table
                            + " WHERE " + column + " IS NOT NULL AND " + column + " <> '' AND "
                            + column + " NOT LIKE 'enc:%'");
        } catch (Exception e) {
            log.warn("Token 迁移扫描失败（{}.{}，表可能不存在）: {}", table, column, e.getMessage());
            return;
        }
        if (rows.isEmpty()) {
            return;
        }
        int migrated = 0;
        for (Map<String, Object> row : rows) {
            Object id = row.get("id");
            String plain = (String) row.get(column);
            if (id == null || plain == null || plain.isBlank()) {
                continue;
            }
            try {
                jdbcTemplate.update("UPDATE " + table + " SET " + column + " = ? WHERE id = ?",
                        tokenCrypto.encrypt(plain), id);
                migrated++;
            } catch (Exception e) {
                log.error("Token 迁移失败（{}.{} id={}）: {}", table, column, id, e.getMessage());
            }
        }
        if (migrated > 0) {
            log.info("Token 加密迁移完成: {}.{} 共迁移 {} 行", table, column, migrated);
        }
    }
}
