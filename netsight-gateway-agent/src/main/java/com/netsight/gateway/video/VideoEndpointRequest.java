package com.netsight.gateway.video;

import lombok.Data;

/**
 * 视频端点保存请求。password 为明文，仅在本机 API 内出现；null/空表示沿用旧密码。
 */
@Data
public class VideoEndpointRequest {
    private String deviceName;
    private Boolean enabled;
    private String vendor;
    private String host;
    private Integer port;
    private Integer channel;
    private String username;
    private String password;
    private boolean clearPassword;
    private String mainPath;
    private String subPath;
    private String transport;
    private Boolean ptzEnabled;
}
