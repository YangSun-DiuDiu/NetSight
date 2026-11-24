-- NetSight v1.3.2：设备清单权威源下沉到边缘网关
ALTER TABLE device
  ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'cloud' COMMENT '来源：edge/cloud' AFTER collect_port,
  ADD COLUMN synced_at DATETIME NULL COMMENT '最后同步时间' AFTER source;

ALTER TABLE edge_gateway
  ADD COLUMN inventory_revision BIGINT NOT NULL DEFAULT 0 COMMENT '设备清单最后成功应用的 revision' AFTER status,
  ADD COLUMN inventory_synced_at DATETIME NULL COMMENT '设备清单最后同步时间' AFTER inventory_revision,
  ADD COLUMN inventory_device_count INT NOT NULL DEFAULT 0 COMMENT '设备清单一共台数' AFTER inventory_synced_at;

ALTER TABLE device DROP INDEX uk_device_code;
ALTER TABLE device ADD UNIQUE KEY uk_gateway_device (gateway_id, device_code);
