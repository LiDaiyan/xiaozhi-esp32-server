package xiaozhi.modules.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ProjectSaveDTO {
    @NotBlank
    @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]{0,63}", message = "Project-Id 仅允许字母、数字、下划线和连字符，最多64位")
    private String projectId;
    @NotBlank
    @Size(max = 100)
    private String projectName;
    private Boolean enabled = true;
}
