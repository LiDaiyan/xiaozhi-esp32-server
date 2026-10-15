package xiaozhi.modules.project.dto;

import java.util.List;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import xiaozhi.modules.agent.dto.ContextProviderDTO;

@Data
public class ProjectContextProvidersDTO {
    @NotNull(message = "上下文源列表不能为空，清空请传入空数组")
    @Size(max = 20, message = "每个项目最多配置20个上下文源")
    private List<@NotNull ContextProviderDTO> contextProviders;
}
