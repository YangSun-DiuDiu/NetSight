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
