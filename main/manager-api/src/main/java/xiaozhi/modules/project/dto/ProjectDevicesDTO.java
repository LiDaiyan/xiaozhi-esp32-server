package xiaozhi.modules.project.dto;

import java.util.List;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ProjectDevicesDTO {
    @NotEmpty
    @Size(max = 5000)
    private List<String> macAddresses;
}
