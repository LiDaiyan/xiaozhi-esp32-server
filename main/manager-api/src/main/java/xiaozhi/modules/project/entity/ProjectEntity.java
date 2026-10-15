package xiaozhi.modules.project.entity;

import java.util.Date;
import java.util.List;
import com.baomidou.mybatisplus.annotation.TableField;
import xiaozhi.modules.agent.dto.ContextProviderDTO;
import xiaozhi.modules.agent.typehandler.ContextProviderListTypeHandler;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName(value = "ai_project", autoResultMap = true)
public class ProjectEntity {
    @TableId(type = IdType.INPUT)
    private String projectId;
    private String projectName;
    private Long userId;
    private Integer enabled;
    private Date createdAt;
    @TableField(typeHandler = ContextProviderListTypeHandler.class)
    private List<ContextProviderDTO> contextProviders;
}
