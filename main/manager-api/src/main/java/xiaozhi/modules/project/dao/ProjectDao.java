package xiaozhi.modules.project.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import xiaozhi.modules.project.entity.ProjectEntity;

@Mapper
public interface ProjectDao extends BaseMapper<ProjectEntity> {
    @Select("SELECT * FROM ai_project WHERE project_id = #{projectId} FOR UPDATE")
    ProjectEntity lockById(@Param("projectId") String projectId);
}
