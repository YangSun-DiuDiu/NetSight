package com.netsight.gateway.sync;

import com.netsight.gateway.core.ConfigHolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MappingSyncTest {

    @TempDir
    Path temp;

    @Test
    void reloadLocalWritesPingTargets() throws Exception {
        Path mapping = temp.resolve("mapping.json");
        LocalInventoryStore store = new LocalInventoryStore(mapping);
        MappingDevice device = new MappingDevice();
        device.setDeviceCode("DEV-GW1-0001");
        device.setDeviceName("测试设备");
        device.setDeviceIp("10.0.0.1");
        device.setDeviceType("camera");
        store.mutate(doc -> {
            doc.getDevices().add(device);
            return null;
        });

        ConfigHolder holder = mock(ConfigHolder.class);
        when(holder.getTargetsDir()).thenReturn(temp.resolve("targets").toString());
        MappingSync sync = new MappingSync(holder, store, new com.netsight.gateway.core.RuntimeState());

        sync.reloadLocal();

        String ping = Files.readString(temp.resolve("targets").resolve("ping.json"));
        assertTrue(ping.contains("10.0.0.1"));
        assertEquals(1, sync.getDevices().size());
    }
}
