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
