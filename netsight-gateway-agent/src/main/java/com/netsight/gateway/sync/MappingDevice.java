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
