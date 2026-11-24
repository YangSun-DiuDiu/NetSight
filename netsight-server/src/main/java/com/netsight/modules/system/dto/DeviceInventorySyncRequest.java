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
