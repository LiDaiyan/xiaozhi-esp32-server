package xiaozhi.modules.agent.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.agent.entity.AgentTemplateEntity;
import xiaozhi.modules.agent.service.AgentPluginMappingService;
import xiaozhi.modules.agent.service.AgentSnapshotService;
import xiaozhi.modules.agent.service.AgentTemplateService;
import xiaozhi.modules.model.service.ModelConfigService;
import xiaozhi.modules.model.service.ModelProviderService;
import xiaozhi.modules.timbre.service.TimbreService;

class AgentProjectCreationTest {
    @Test
    void anonymousOtaCreationUsesProjectOwnerAndTheExistingDefaultTemplate() {
        AgentDao dao = mock(AgentDao.class);
        AgentTemplateService templates = mock(AgentTemplateService.class);
        AgentSnapshotService snapshots = mock(AgentSnapshotService.class);
        AgentServiceImpl service = new AgentServiceImpl(dao, null, mock(TimbreService.class), mock(ModelConfigService.class),
                null, null, mock(AgentPluginMappingService.class), null, templates,
                mock(ModelProviderService.class), null, null, null, snapshots);
        ReflectionTestUtils.setField(service, "baseDao", dao);
        AgentTemplateEntity template = new AgentTemplateEntity();
        template.setAsrModelId("ASR_aliyun");
        template.setLlmModelId("LLM_qwen");
        template.setTtsModelId("TTS_aliyun");
        template.setTtsVoiceId("voice1");
        template.setSystemPrompt("项目默认提示词");
        when(templates.getDefaultTemplate()).thenReturn(template);
        when(dao.insert(any(AgentEntity.class))).thenReturn(1);
        String id = service.createAgentForProject("设备一", 7L, "p1");
        ArgumentCaptor<AgentEntity> capture = ArgumentCaptor.forClass(AgentEntity.class);
        verify(dao).insert(capture.capture());
        AgentEntity agent = capture.getValue();
        assertEquals(7L, agent.getUserId());
        assertEquals(7L, agent.getCreator());
        assertEquals("p1", agent.getProjectId());
        assertEquals("ASR_aliyun", agent.getAsrModelId());
        assertEquals("LLM_qwen", agent.getLlmModelId());
        assertEquals("TTS_aliyun", agent.getTtsModelId());
        assertEquals("voice1", agent.getTtsVoiceId());
        assertEquals("项目默认提示词", agent.getSystemPrompt());
        verify(snapshots).createSnapshot(id, "initial");
    }
}
