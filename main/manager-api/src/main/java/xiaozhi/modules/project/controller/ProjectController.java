package xiaozhi.modules.project.controller;

import java.util.List;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.project.dto.ProjectDevicesDTO;
import xiaozhi.modules.project.dto.ProjectSaveDTO;
import xiaozhi.modules.project.entity.ProjectDeviceEntity;
import xiaozhi.modules.project.entity.ProjectEntity;
import xiaozhi.modules.project.service.ProjectService;
import xiaozhi.modules.security.user.SecurityUser;

@RestController
@RequestMapping("/project")
@RequiredArgsConstructor
@RequiresPermissions("sys:role:normal")
public class ProjectController {
    private final ProjectService projectService;

    @GetMapping
    public Result<List<ProjectEntity>> list() {
        return new Result<List<ProjectEntity>>().ok(projectService.list(SecurityUser.getUserId()));
    }

    @PostMapping
    public Result<Void> create(@RequestBody @Valid ProjectSaveDTO dto) {
        projectService.create(dto, SecurityUser.getUserId());
        return new Result<>();
    }

    @PutMapping("/{projectId}")
    public Result<Void> update(@PathVariable String projectId, @RequestBody @Valid ProjectSaveDTO dto) {
        projectService.update(projectId, dto, SecurityUser.getUserId());
        return new Result<>();
    }

    @GetMapping("/{projectId}/devices")
    public Result<List<ProjectDeviceEntity>> devices(@PathVariable String projectId) {
        return new Result<List<ProjectDeviceEntity>>().ok(projectService.devices(projectId, SecurityUser.getUserId()));
    }

    @PostMapping("/{projectId}/devices")
    public Result<Void> importDevices(@PathVariable String projectId, @RequestBody @Valid ProjectDevicesDTO dto) {
        projectService.importDevices(projectId, dto.getMacAddresses(), SecurityUser.getUserId());
        return new Result<>();
    }

    @DeleteMapping("/{projectId}/devices/{macAddress}")
    public Result<Void> removeDevice(@PathVariable String projectId, @PathVariable String macAddress) {
        projectService.removeDevice(projectId, macAddress, SecurityUser.getUserId());
        return new Result<>();
    }
}
