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
