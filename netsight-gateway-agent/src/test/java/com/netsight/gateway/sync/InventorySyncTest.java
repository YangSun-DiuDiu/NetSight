package com.netsight.gateway.sync;

import com.netsight.gateway.connector.CloudClient;
import com.netsight.gateway.core.ConfigHolder;
import com.netsight.gateway.core.RuntimeState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InventorySyncTest {

    @TempDir
    Path temp;

    @Test
    void sendsFullBodyWithRevisionAndDeviceCount() {
        ConfigHolder holder = mock(ConfigHolder.class);
        ConfigHolder.GatewayConfig cfg = new ConfigHolder.GatewayConfig();
        cfg.setGatewayCode("GW-TEST-01");
        cfg.setLocal(new ConfigHolder.Local());
        when(holder.getConfig()).thenReturn(cfg);

        LocalInventoryStore store = new LocalInventoryStore(temp.resolve("mapping.json"));
        MappingDevice device = new MappingDevice();
        device.setDeviceCode("DEV-GW-TEST-01-0001");
        device.setDeviceName("测试枪机");
        device.setDeviceIp("10.0.0.1");
        device.setDeviceType("camera");
        store.mutate(doc -> {
            doc.getDevices().add(device);
            return null;
        });
        long revision = store.getRevision();

        CloudClient cloud = mock(CloudClient.class);
        when(cloud.syncDevices(anyString()))
                .thenReturn(new CloudClient.SyncResponse(200, "{\"code\":200,\"data\":{}}"));
        RuntimeState state = new RuntimeState();
        InventorySync sync = new InventorySync(holder, store, cloud, state);

        sync.syncOnce(false);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(cloud).syncDevices(captor.capture());
        assertTrue(captor.getValue().contains("\"revision\":" + revision));
        assertTrue(captor.getValue().contains("\"deviceCode\":\"DEV-GW-TEST-01-0001\""));
        assertEquals("success", state.getInventorySyncStatus());
    }

    @Test
    void blocksEmptyListWithoutAllowEmpty() {
        ConfigHolder holder = mock(ConfigHolder.class);
        ConfigHolder.GatewayConfig cfg = new ConfigHolder.GatewayConfig();
        cfg.setGatewayCode("GW-TEST-01");
        cfg.setLocal(new ConfigHolder.Local());
        when(holder.getConfig()).thenReturn(cfg);

        LocalInventoryStore store = new LocalInventoryStore(temp.resolve("mapping.json"));
        CloudClient cloud = mock(CloudClient.class);
        RuntimeState state = new RuntimeState();
        InventorySync sync = new InventorySync(holder, store, cloud, state);

        sync.syncOnce(false);

        verifyNoInteractions(cloud);
        assertEquals("empty_blocked", state.getInventorySyncStatus());
    }
}
