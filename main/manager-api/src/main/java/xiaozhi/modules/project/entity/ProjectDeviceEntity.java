package xiaozhi.modules.project.entity;

import java.util.Date;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("ai_project_device")
public class ProjectDeviceEntity {
    @TableId(type = IdType.INPUT)
    private String macAddress;
    private String projectId;
    private Date createdAt;
}
