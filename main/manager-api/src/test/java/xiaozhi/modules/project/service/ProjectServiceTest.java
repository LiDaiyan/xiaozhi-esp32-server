package xiaozhi.modules.project.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.redis.RedisUtils;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.agent.service.AgentService;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.dto.DeviceReportReqDTO;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.project.dao.ProjectDao;
import xiaozhi.modules.project.dao.ProjectDeviceDao;
import xiaozhi.modules.project.entity.ProjectDeviceEntity;
import xiaozhi.modules.project.entity.ProjectEntity;

class ProjectServiceTest {
    private static final String MAC = "aa:bb:cc:dd:ee:ff";
    private final ProjectDao projects = mock(ProjectDao.class);
    private final ProjectDeviceDao whitelist = mock(ProjectDeviceDao.class);
    private final DeviceDao devices = mock(DeviceDao.class);
    private final AgentDao agents = mock(AgentDao.class);
    private final AgentService agentService = mock(AgentService.class);
    private final RedisUtils redis = mock(RedisUtils.class);
    private final ProjectService service = new ProjectService(projects, whitelist, devices, agents, agentService, redis);

    private ProjectEntity allowedProject() {
        ProjectEntity project = new ProjectEntity();
        project.setProjectId("p1"); project.setProjectName("工厂一");
        project.setUserId(7L); project.setEnabled(1);
        when(projects.lockById("p1")).thenReturn(project);
        when(projects.selectById("p1")).thenReturn(project);
        ProjectDeviceEntity entry = new ProjectDeviceEntity();
        entry.setProjectId("p1"); entry.setMacAddress(MAC);
        when(whitelist.selectById(MAC)).thenReturn(entry);
        return project;
    }

    private DeviceEntity boundDevice(String projectId, long userId) {
        DeviceEntity device = new DeviceEntity();
        device.setId(MAC); device.setMacAddress(MAC); device.setAgentId("agent1"); device.setUserId(userId);
        AgentEntity agent = new AgentEntity();
        agent.setId("agent1"); agent.setProjectId(projectId); agent.setUserId(userId);
        when(agents.selectById("agent1")).thenReturn(agent);
        when(devices.selectOne(any(QueryWrapper.class))).thenReturn(device);
        return device;
    }

    @Test
    void provisionsUsingProjectOwnerAndDeviceHeaderRatherThanReportBody() {
        allowedProject();
        when(agentService.createAgentForProject(anyString(), eq(7L), eq("p1"))).thenReturn("agent1");
        DeviceReportReqDTO report = new DeviceReportReqDTO();
        report.setMacAddress("11:22:33:44:55:66");
        DeviceEntity device = service.provision("p1", "AA-BB-CC-DD-EE-FF", report);
        assertEquals(MAC, device.getMacAddress());
        assertEquals(7L, device.getUserId());
        assertEquals("agent1", device.getAgentId());
        assertEquals(1, device.getAutoUpdate());
        verify(agentService).createAgentForProject("工厂一 / " + MAC, 7L, "p1");
        verify(devices).insert(device);
    }

    @Test
    void repeatedOtaReusesExistingAgent() {
        allowedProject();
        DeviceEntity existing = boundDevice("p1", 7L);
        assertSame(existing, service.provision("p1", MAC, null));
        assertSame(existing, service.provision("p1", MAC, null));
        verifyNoInteractions(agentService);
        verify(devices, never()).insert(any(DeviceEntity.class));
    }

    @Test
    void deniesUnknownWrongDisabledOrRemovedProjectMembershipBeforeCreatingAgent() {
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> service.provision("missing", MAC, null)).getStatusCode().value());
        ProjectEntity project = allowedProject();
        assertThrows(ResponseStatusException.class, () -> service.provision("p2", MAC, null));
        project.setEnabled(0);
        assertThrows(ResponseStatusException.class, () -> service.provision("p1", MAC, null));
        project.setEnabled(1);
        when(whitelist.selectById(MAC)).thenReturn(null);
        assertThrows(ResponseStatusException.class, () -> service.provision("p1", MAC, null));
        verifyNoInteractions(agentService);
    }

    @Test
    void rejectsConflictingOwnerOrProjectBinding() {
        allowedProject();
        boundDevice("p2", 7L);
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> service.provision("p1", MAC, null)).getStatusCode().value());
        boundDevice("p1", 8L);
        assertThrows(ResponseStatusException.class, () -> service.provision("p1", MAC, null));
        verifyNoInteractions(agentService);
    }

    @Test
    void omittingHeaderCannotBypassProjectAuthorizationEvenAfterMacRemoval() {
        allowedProject();
        assertThrows(ResponseStatusException.class, () -> service.requireLegacyDevice(MAC));
        when(whitelist.selectById(MAC)).thenReturn(null);
        boundDevice("p1", 7L);
        assertThrows(ResponseStatusException.class, () -> service.requireLegacyDevice(MAC));
    }

    @Test
    void revocationBlocksPrivateConfigurationOfPreviouslyBoundDevice() {
        ProjectEntity project = allowedProject();
        DeviceEntity device = boundDevice("p1", 7L);
        assertDoesNotThrow(() -> service.requireBoundDevice(device));
        project.setEnabled(0);
        assertThrows(ResponseStatusException.class, () -> service.requireBoundDevice(device));
        project.setEnabled(1);
        when(whitelist.selectById(MAC)).thenReturn(null);
        assertThrows(ResponseStatusException.class, () -> service.requireBoundDevice(device));
    }

    @Test
    void whitelistManagementRequiresOwnerAndNormalizesDuplicates() {
        allowedProject();
        assertThrows(RenException.class, () -> service.importDevices("p1", List.of(MAC), 8L));
        when(whitelist.selectById(MAC)).thenReturn(null);
        service.importDevices("p1", List.of(MAC, "AA-BB-CC-DD-EE-FF"), 7L);
        ArgumentCaptor<ProjectDeviceEntity> entry = ArgumentCaptor.forClass(ProjectDeviceEntity.class);
        verify(whitelist, times(1)).insert(entry.capture());
        assertEquals(MAC, entry.getValue().getMacAddress());
    }

    @Test
    void invalidBatchAndMacInAnotherProjectAreRejected() {
        allowedProject();
        assertThrows(RenException.class, () -> service.importDevices("p1", List.of(MAC, "invalid"), 7L));
        verify(whitelist, never()).insert(any(ProjectDeviceEntity.class));
        ProjectDeviceEntity other = new ProjectDeviceEntity(); other.setProjectId("p2");
        when(whitelist.selectById(MAC)).thenReturn(other);
        assertThrows(RenException.class, () -> service.importDevices("p1", List.of(MAC), 7L));
        verify(whitelist, never()).insert(any(ProjectDeviceEntity.class));
    }

    @Test
    void acceptsLegacyDeviceAndRejectsNonHexMac() {
        assertDoesNotThrow(() -> service.requireLegacyDevice(MAC));
        assertThrows(RenException.class, () -> ProjectService.normalizeMac("zz:bb:cc:dd:ee:ff"));
    }
}
