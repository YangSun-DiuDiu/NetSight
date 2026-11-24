package com.netsight.modules.system.service;

import com.netsight.common.exception.ServiceException;
import com.netsight.common.exception.StaleRevisionException;
import com.netsight.modules.system.dto.DeviceInventorySyncRequest;
import com.netsight.modules.system.entity.Device;
import com.netsight.modules.system.entity.EdgeGateway;
import com.netsight.modules.system.mapper.DeviceMapper;
import com.netsight.modules.system.mapper.EdgeGatewayMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceInventorySyncServiceTest {

    private final DeviceMapper deviceMapper = mock(DeviceMapper.class);
    private final EdgeGatewayMapper gatewayMapper = mock(EdgeGatewayMapper.class);
    private final DeviceInventorySyncService service = new DeviceInventorySyncService(gatewayMapper, deviceMapper);

    private EdgeGateway gateway(long revision) {
        EdgeGateway g = new EdgeGateway();
        g.setId(14L);
        g.setTenantId(34L);
        g.setGatewayCode("GW19920261122192456");
        g.setInventoryRevision(revision);
        return g;
    }

    private DeviceInventorySyncRequest request(long revision, String... codes) {
        DeviceInventorySyncRequest req = new DeviceInventorySyncRequest();
        req.setGatewayCode("GW19920261122192456");
        req.setRevision(revision);
        req.setFull(true);
        req.setAllowEmpty(false);
        for (String code : codes) {
            DeviceInventorySyncRequest.Item item = new DeviceInventorySyncRequest.Item();
            item.setDeviceCode(code);
            item.setDeviceName("设备-" + code);
            item.setDeviceIp("10.0.0.1");
            item.setDeviceType("camera");
            req.getDevices().add(item);
        }
        return req;
    }

    @Test
    void rejectsStaleRevision() {
        StaleRevisionException ex = assertThrows(StaleRevisionException.class,
                () -> service.sync(gateway(5), request(4, "DEV-1")));
        assertEquals(5, ex.getCurrentRevision());
    }

    @Test
    void rejectsEmptyListWithoutAllowEmpty() {
        DeviceInventorySyncRequest req = request(6);
        assertThrows(ServiceException.class, () -> service.sync(gateway(5), req));
    }

    @Test
    void insertsNewDeviceWithEdgeSource() {
        when(deviceMapper.selectByGatewayAndCodeIncludingDeleted(14L, "DEV-1")).thenReturn(null);

        DeviceInventorySyncRequest req = request(6, "DEV-1");
        service.sync(gateway(5), req);

        ArgumentCaptor<Device> deviceCaptor = ArgumentCaptor.forClass(Device.class);
        verify(deviceMapper).insert(deviceCaptor.capture());
        Device inserted = deviceCaptor.getValue();
        assertEquals("DEV-1", inserted.getDeviceCode());
        assertEquals("edge", inserted.getSource());
        assertEquals(34L, inserted.getTenantId());
        assertEquals(14L, inserted.getGatewayId());

        ArgumentCaptor<EdgeGateway> gatewayCaptor = ArgumentCaptor.forClass(EdgeGateway.class);
        verify(gatewayMapper).updateById(gatewayCaptor.capture());
        assertEquals(6L, gatewayCaptor.getValue().getInventoryRevision());
    }

    @Test
    void softDeletesDeviceMissingFromFullList() {
        Device old = new Device();
        old.setId(100L);
        old.setDeviceCode("DEV-OLD");
        old.setGatewayId(14L);
        when(deviceMapper.selectList(any())).thenReturn(List.of(old));
        when(deviceMapper.selectByGatewayAndCodeIncludingDeleted(14L, "DEV-1")).thenReturn(null);

        service.sync(gateway(5), request(6, "DEV-1"));

        verify(deviceMapper).deleteById(100L);
    }
}
