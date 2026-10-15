package xiaozhi.modules.config.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xiaozhi.common.redis.RedisUtils;
import xiaozhi.modules.agent.dao.AgentVoicePrintDao;
import xiaozhi.modules.agent.dto.ContextProviderDTO;
import xiaozhi.modules.agent.entity.AgentContextProviderEntity;
import xiaozhi.modules.agent.service.*;
import xiaozhi.modules.agent.vo.AgentInfoVO;
import xiaozhi.modules.correctword.service.CorrectWordFileService;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.device.service.DeviceService;
import xiaozhi.modules.model.service.ModelConfigService;
import xiaozhi.modules.project.service.ProjectService;
import xiaozhi.modules.sys.service.SysParamsService;
import xiaozhi.modules.timbre.service.TimbreService;
import xiaozhi.modules.voiceclone.service.VoiceCloneService;

class ConfigProjectContextTest {
    private static final String MAC = "aa:bb:cc:dd:ee:ff";
    private final DeviceService devices = mock(DeviceService.class);
    private final AgentService agents = mock(AgentService.class);
    private final AgentContextProviderService agentContexts = mock(AgentContextProviderService.class);
    private final ProjectService projects = mock(ProjectService.class);
    private final AgentInfoVO agent = new AgentInfoVO();
    private final ConfigServiceImpl service = new ConfigServiceImpl(mock(SysParamsService.class), devices,
            mock(ModelConfigService.class), agents, mock(AgentTemplateService.class), mock(RedisUtils.class),
            mock(TimbreService.class), mock(AgentPluginMappingService.class), mock(AgentMcpAccessPointService.class),
            agentContexts, mock(VoiceCloneService.class), mock(AgentVoicePrintDao.class),
            mock(CorrectWordFileService.class), projects);

    @BeforeEach
    void deviceBinding() {
        DeviceEntity device = new DeviceEntity();
        device.setAgentId("agent1");
        when(devices.getDeviceByMacAddress(MAC)).thenReturn(device);
        agent.setId("agent1"); agent.setProjectId("p1"); agent.setUserId(7L);
        agent.setIntentModelId("Intent_nointent");
        when(agents.getAgentById("agent1")).thenReturn(agent);
    }

    private ContextProviderDTO provider(String url) {
        ContextProviderDTO provider = new ContextProviderDTO();
        provider.setUrl(url);
        provider.setHeaders(Map.of("X-Project", "p1"));
        return provider;
    }

    @Test
    void newlyProvisionedAgentInheritsCurrentProjectDefault() {
        List<ContextProviderDTO> defaults = List.of(provider("https://example.com/p1/context"));
        when(projects.getDefaultContextProviders("p1", 7L)).thenReturn(defaults);
        assertEquals(defaults, service.getAgentModels(MAC, Map.of()).get("context_providers"));
    }

    @Test
    void individualOverrideTakesPrecedenceWithoutMergingProjectDefaults() {
        AgentContextProviderEntity context = new AgentContextProviderEntity();
        context.setContextProviders(List.of(provider("https://example.com/agent/context")));
        when(agentContexts.getByAgentId("agent1")).thenReturn(context);
        assertEquals(context.getContextProviders(), service.getAgentModels(MAC, Map.of()).get("context_providers"));
        verify(projects, never()).getDefaultContextProviders(any(), any());
    }

    @Test
    void emptyIndividualConfigurationInheritsProjectAndSubsequentConnectionUsesUpdatedDefault() {
        AgentContextProviderEntity context = new AgentContextProviderEntity();
        context.setContextProviders(List.of());
        when(agentContexts.getByAgentId("agent1")).thenReturn(context);
        List<ContextProviderDTO> first = List.of(provider("https://example.com/first"));
        List<ContextProviderDTO> second = List.of(provider("https://example.com/second"));
        when(projects.getDefaultContextProviders("p1", 7L)).thenReturn(first, second, List.of());
        assertEquals(first, service.getAgentModels(MAC, Map.of()).get("context_providers"));
        assertEquals(second, service.getAgentModels(MAC, Map.of()).get("context_providers"));
        assertFalse(service.getAgentModels(MAC, Map.of()).containsKey("context_providers"));
    }

    @Test
    void ungroupedAgentDoesNotAcquireAnyProjectConfiguration() {
        agent.setProjectId(null);
        assertFalse(service.getAgentModels(MAC, Map.of()).containsKey("context_providers"));
    }
}
