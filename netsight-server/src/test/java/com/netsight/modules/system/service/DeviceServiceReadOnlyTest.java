package com.netsight.modules.system.service;

import com.netsight.common.exception.ServiceException;
import com.netsight.modules.system.entity.Device;
import com.netsight.modules.system.mapper.DeviceMapper;
import com.netsight.modules.system.mapper.DeviceTopologyMapper;
import com.netsight.modules.system.mapper.EdgeGatewayMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class DeviceServiceReadOnlyTest {

    private final DeviceService service = new DeviceService(
            mock(DeviceMapper.class), mock(EdgeGatewayMapper.class), mock(DeviceTopologyMapper.class));

    @Test
    void addIsRejected() {
        ServiceException ex = assertThrows(ServiceException.class, () -> service.addDevice(new Device()));
        assertEquals(403, ex.getCode());
    }

    @Test
    void updateIsRejected() {
        Device d = new Device();
        d.setId(1L);
        ServiceException ex = assertThrows(ServiceException.class, () -> service.updateDevice(d));
        assertEquals(403, ex.getCode());
    }

    @Test
    void deleteIsRejected() {
        ServiceException ex = assertThrows(ServiceException.class, () -> service.deleteDevice(1L));
        assertEquals(403, ex.getCode());
    }
}
