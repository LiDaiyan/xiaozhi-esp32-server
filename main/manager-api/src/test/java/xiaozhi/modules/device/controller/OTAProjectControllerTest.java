package xiaozhi.modules.device.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import xiaozhi.modules.device.dto.DeviceReportReqDTO;
import xiaozhi.modules.device.dto.DeviceReportRespDTO;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.device.service.DeviceService;
import xiaozhi.modules.project.service.ProjectService;
import xiaozhi.modules.sys.service.SysParamsService;

class OTAProjectControllerTest {
    private static final String MAC = "aa:bb:cc:dd:ee:ff";
    private final DeviceService devices = mock(DeviceService.class);
    private final ProjectService projects = mock(ProjectService.class);
    private final OTAController controller = new OTAController(devices, mock(SysParamsService.class), projects);
    private final DeviceReportReqDTO report = new DeviceReportReqDTO();

    @Test
    void provisionsBeforeReturningNormalOtaResponseWithoutActivationCode() {
        when(devices.checkDeviceActive(MAC, "client1", report)).thenReturn(new DeviceReportRespDTO());
        ResponseEntity<String> response = controller.checkOTAVersion(report, MAC, "client1", "p1");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertFalse(response.getBody().contains("activation"));
        var order = inOrder(projects, devices);
        order.verify(projects).provision("p1", MAC, report);
        order.verify(devices).checkDeviceActive(MAC, "client1", report);
    }

    @Test
    void forbiddenProjectReturns403WithNoWebsocketTokenOrActivation() {
        when(projects.provision("p1", MAC, report))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Device is not allowed"));
        ResponseEntity<String> response = controller.checkOTAVersion(report, MAC, null, "p1");
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertTrue(response.getBody().contains("Device is not allowed"));
        assertFalse(response.getBody().contains("websocket"));
        assertFalse(response.getBody().contains("activation"));
        verifyNoInteractions(devices);
    }

    @Test
    void missingProjectHeaderIsCheckedBeforeLegacyActivation() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Project-Id is required"))
                .when(projects).requireLegacyDevice(MAC);
        assertEquals(HttpStatus.FORBIDDEN, controller.checkOTAVersion(report, MAC, null, null).getStatusCode());
        verifyNoInteractions(devices);
    }

    @Test
    void preservesLegacyActivationForUnmanagedDevice() {
        when(devices.checkDeviceActive(MAC, MAC, report)).thenReturn(new DeviceReportRespDTO());
        assertEquals(HttpStatus.OK, controller.checkOTAVersion(report, MAC, null, null).getStatusCode());
        verify(projects).requireLegacyDevice(MAC);
        verify(projects, never()).provision(anyString(), anyString(), any());
        verify(devices).checkDeviceActive(MAC, MAC, report);
    }

    @Test
    void activationPollingAlsoEnforcesMembership() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Device is not allowed"))
                .when(projects).requireMembership("p1", MAC);
        assertEquals(HttpStatus.FORBIDDEN, controller.activateDevice(MAC, null, "p1").getStatusCode());
        verifyNoInteractions(devices);
    }

    @Test
    void activationPollingChecksActualBinding() {
        DeviceEntity device = new DeviceEntity();
        when(devices.getDeviceByMacAddress(MAC)).thenReturn(device);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Binding mismatch"))
                .when(projects).requireBoundDevice(device);
        assertEquals(HttpStatus.FORBIDDEN, controller.activateDevice(MAC, null, "p1").getStatusCode());
    }

    @Test
    void malformedMacNeverReachesProvisioning() {
        controller.checkOTAVersion(report, "zz:bb:cc:dd:ee:ff", null, "p1");
        verifyNoInteractions(projects, devices);
    }
}
