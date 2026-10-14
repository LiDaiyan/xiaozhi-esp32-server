package xiaozhi.modules.project.entity;

import java.util.Date;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("ai_project")
public class ProjectEntity {
    @TableId(type = IdType.INPUT)
    private String projectId;
    private String projectName;
    private Long userId;
    private Integer enabled;
    private Date createdAt;
}
