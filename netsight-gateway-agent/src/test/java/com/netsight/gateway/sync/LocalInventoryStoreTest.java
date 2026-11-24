package com.netsight.gateway.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalInventoryStoreTest {

    @TempDir
    Path temp;

    @Test
    void springConstructorIsExplicitlySelected() throws Exception {
        Constructor<?> ctor = LocalInventoryStore.class.getConstructor(
                com.netsight.gateway.core.ConfigHolder.class);
        assertNotNull(ctor.getAnnotation(Autowired.class),
                "多构造器场景必须显式标注 @Autowired，否则 Spring 会寻找无参构造器");
    }

    @Test
    void createsEmptyDocumentWhenFileMissing() {
        LocalInventoryStore store = new LocalInventoryStore(temp.resolve("mapping.json"));
        LocalInventoryDocument doc = store.read();

        assertEquals(1, doc.getRevision());
        assertNotNull(doc.getDevices());
        assertTrue(Files.exists(temp.resolve("mapping.json")));
    }

    @Test
    void mutateIncrementsRevisionAndWritesDevice() {
        LocalInventoryStore store = new LocalInventoryStore(temp.resolve("mapping.json"));
        MappingDevice device = new MappingDevice();
        device.setDeviceCode("DEV-GW1-0001");
        device.setDeviceName("测试设备");
        device.setDeviceIp("10.0.0.1");
        device.setDeviceType("camera");

        store.mutate(doc -> {
            doc.getDevices().add(device);
            return null;
        });

        LocalInventoryDocument after = store.read();
        assertEquals(2, after.getRevision());
        assertEquals(1, after.getDevices().size());
        assertEquals("DEV-GW1-0001", after.getDevices().get(0).getDeviceCode());
    }

    @Test
    void malformedDevicesFieldIsRejected() throws Exception {
        Path file = temp.resolve("mapping.json");
        Files.writeString(file, "{\"revision\":3,\"devices\":\"not-array\"}");
        LocalInventoryStore store = new LocalInventoryStore(file);

        assertThrows(IllegalStateException.class, store::read);
    }
}
