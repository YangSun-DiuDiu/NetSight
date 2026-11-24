package com.netsight.gateway.localapi;

import com.netsight.gateway.core.ConfigHolder;
import com.netsight.gateway.sync.InventorySync;
import com.netsight.gateway.sync.LocalInventoryStore;
import com.netsight.gateway.sync.MappingDevice;
import com.netsight.gateway.sync.MappingSync;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
