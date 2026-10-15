package xiaozhi.modules.project.service;

import java.net.URI;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.redis.RedisKeys;
import xiaozhi.common.redis.RedisUtils;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.dto.ContextProviderDTO;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.agent.service.AgentService;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.dto.DeviceReportReqDTO;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.project.dao.ProjectDao;
import xiaozhi.modules.project.dao.ProjectDeviceDao;
import xiaozhi.modules.project.dto.ProjectSaveDTO;
import xiaozhi.modules.project.entity.ProjectDeviceEntity;
import xiaozhi.modules.project.entity.ProjectEntity;

/** Project IDs are routing identifiers; authorization requires membership in the MAC whitelist. */
@Service
@RequiredArgsConstructor
public class ProjectService {
    private final ProjectDao projectDao;
    private final ProjectDeviceDao projectDeviceDao;
    private final DeviceDao deviceDao;
    private final AgentDao agentDao;
    private final AgentService agentService;
    private final RedisUtils redisUtils;

    public static String normalizeMac(String mac) {
        if (mac == null || !mac.trim().matches("(?i)^([0-9a-f]{2}[:-]){5}[0-9a-f]{2}$")) {
            throw new RenException("MAC 地址格式错误: " + mac);
        }
        return mac.trim().replace('-', ':').toLowerCase(Locale.ROOT);
    }

    public List<ProjectEntity> list(Long userId) {
        return projectDao.selectList(new QueryWrapper<ProjectEntity>().eq("user_id", userId)
                .orderByAsc("created_at", "project_id"));
    }

    private ProjectEntity owned(String projectId, Long userId, boolean lock) {
        ProjectEntity project = lock ? projectDao.lockById(projectId) : projectDao.selectById(projectId);
        if (project == null || !Objects.equals(project.getUserId(), userId)) {
            throw new RenException("项目不存在或无权操作");
        }
        return project;
    }

    @Transactional(rollbackFor = Exception.class)
    public void create(ProjectSaveDTO dto, Long userId) {
        ProjectEntity project = new ProjectEntity();
        project.setProjectId(dto.getProjectId());
        project.setProjectName(dto.getProjectName().trim());
        project.setUserId(userId);
        project.setEnabled(Boolean.TRUE.equals(dto.getEnabled()) ? 1 : 0);
        project.setCreatedAt(new Date());
        try {
            projectDao.insert(project);
        } catch (DuplicateKeyException e) {
            throw new RenException("Project-Id 已存在");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(String projectId, ProjectSaveDTO dto, Long userId) {
        ProjectEntity project = owned(projectId, userId, true);
        if (!projectId.equals(dto.getProjectId())) {
            throw new RenException("Project-Id 创建后不能修改");
        }
        project.setProjectName(dto.getProjectName().trim());
        project.setEnabled(Boolean.TRUE.equals(dto.getEnabled()) ? 1 : 0);
        projectDao.updateById(project);
    }

    public List<ContextProviderDTO> getContextProviders(String projectId, Long userId) {
        ProjectEntity project = owned(projectId, userId, false);
        return project.getContextProviders() == null ? Collections.emptyList() : project.getContextProviders();
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateContextProviders(String projectId, List<ContextProviderDTO> providers, Long userId) {
        ProjectEntity project = owned(projectId, userId, true);
        if (providers == null || providers.size() > 20) {
            throw new RenException("请提供上下文源列表，每个项目最多20个，清空请传入空数组");
        }
        for (ContextProviderDTO provider : providers) {
            try {
                URI uri = URI.create(provider == null || provider.getUrl() == null ? "" : provider.getUrl());
                if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                        || uri.getHost() == null || uri.getFragment() != null) {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException e) {
                throw new RenException("上下文源地址必须是有效的 HTTP 或 HTTPS URL");
            }
        }
        project.setContextProviders(providers);
        projectDao.updateById(project);
    }

    /** Resolve on each device connection; project defaults are never copied into agent overrides. */
    public List<ContextProviderDTO> getDefaultContextProviders(String projectId, Long userId) {
        if (projectId == null || projectId.isBlank()) {
            return Collections.emptyList();
        }
        ProjectEntity project = projectDao.selectById(projectId);
        if (project == null || !Integer.valueOf(1).equals(project.getEnabled())
                || !Objects.equals(project.getUserId(), userId) || project.getContextProviders() == null) {
            return Collections.emptyList();
        }
        return project.getContextProviders();
    }

    public List<ProjectDeviceEntity> devices(String projectId, Long userId) {
        owned(projectId, userId, false);
        return projectDeviceDao.selectList(new QueryWrapper<ProjectDeviceEntity>()
                .eq("project_id", projectId).orderByAsc("mac_address"));
    }

    /** Additive import: validate the full batch before writing, and roll back conflicts. */
    @Transactional(rollbackFor = Exception.class)
    public void importDevices(String projectId, List<String> macs, Long userId) {
        owned(projectId, userId, true);
        List<String> normalized = macs.stream().map(ProjectService::normalizeMac).distinct().sorted().toList();
        for (String mac : normalized) {
            ProjectDeviceEntity existing = projectDeviceDao.selectById(mac);
            if (existing != null) {
                if (!projectId.equals(existing.getProjectId())) {
                    throw new RenException("MAC 已属于其他项目: " + mac);
                }
                continue;
            }
            DeviceEntity boundDevice = findDevice(mac);
            if (boundDevice != null && !bindingMatches(boundDevice, projectId, userId)) {
                throw new RenException("MAC 已绑定到其他项目或旧智能体，请先解绑: " + mac);
            }
            ProjectDeviceEntity entry = new ProjectDeviceEntity();
            entry.setMacAddress(mac);
            entry.setProjectId(projectId);
            entry.setCreatedAt(new Date());
            try {
                projectDeviceDao.insert(entry);
            } catch (DuplicateKeyException e) {
                throw new RenException("MAC 已被其他项目导入: " + mac);
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void removeDevice(String projectId, String mac, Long userId) {
        owned(projectId, userId, true);
        projectDeviceDao.delete(new QueryWrapper<ProjectDeviceEntity>()
                .eq("project_id", projectId).eq("mac_address", normalizeMac(mac)));
    }

    private DeviceEntity findDevice(String mac) {
        return deviceDao.selectOne(new QueryWrapper<DeviceEntity>()
                .in("mac_address", List.of(mac, mac.replace(':', '-'))).last("LIMIT 1"));
    }

    private boolean bindingMatches(DeviceEntity device, String projectId, Long userId) {
        AgentEntity agent = agentDao.selectById(device.getAgentId());
        return agent != null && projectId.equals(agent.getProjectId())
                && Objects.equals(userId, agent.getUserId()) && Objects.equals(userId, device.getUserId());
    }

    /** Avoid bypassing project authorization by omitting Project-Id on a subsequent request. */
    public void requireLegacyDevice(String mac) {
        String normalized = normalizeMac(mac);
        if (projectDeviceDao.selectById(normalized) != null) {
            throw denied("Project-Id is required for this device");
        }
        DeviceEntity device = findDevice(normalized);
        if (device != null) {
            AgentEntity agent = agentDao.selectById(device.getAgentId());
            if (agent != null && agent.getProjectId() != null) {
                throw denied("Project-Id is required for this device");
            }
        }
    }

    public void requireMembership(String projectId, String mac) {
        ProjectEntity project = projectDao.selectById(projectId);
        ProjectDeviceEntity entry = projectDeviceDao.selectById(normalizeMac(mac));
        if (project == null || !Integer.valueOf(1).equals(project.getEnabled())
                || entry == null || !projectId.equals(entry.getProjectId())) {
            throw denied("Device is not allowed in this project");
        }
    }

    /** Recheck on voice configuration fetch so a previously issued token cannot bypass revocation. */
    public void requireBoundDevice(DeviceEntity device) {
        AgentEntity agent = agentDao.selectById(device.getAgentId());
        ProjectDeviceEntity entry = projectDeviceDao.selectById(normalizeMac(device.getMacAddress()));
        String projectId = agent == null ? null : agent.getProjectId();
        if (projectId == null && entry == null) {
            return;
        }
        if (projectId == null) {
            throw denied("Device binding does not match its project");
        }
        requireMembership(projectId, device.getMacAddress());
        ProjectEntity project = projectDao.selectById(projectId);
        if (!bindingMatches(device, projectId, project.getUserId())) {
            throw denied("Device binding does not match its project");
        }
    }

    /** Project row locking makes first boot idempotent across concurrent OTA requests/instances. */
    @Transactional(rollbackFor = Exception.class)
    public DeviceEntity provision(String projectId, String mac, DeviceReportReqDTO report) {
        ProjectEntity project = projectDao.lockById(projectId);
        requireMembership(projectId, mac);
        String normalized = normalizeMac(mac);
        DeviceEntity existing = findDevice(normalized);
        if (existing != null) {
            if (!bindingMatches(existing, projectId, project.getUserId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Device is already bound to another agent");
            }
            return existing;
        }
        String name = project.getProjectName();
        name = name.substring(0, name.offsetByCodePoints(0, Math.min(44, name.codePointCount(0, name.length()))));
        String agentId = agentService.createAgentForProject(name + " / " + normalized,
                project.getUserId(), projectId);
        Date now = new Date();
        DeviceEntity device = new DeviceEntity();
        device.setId(normalized);
        device.setMacAddress(normalized);
        device.setUserId(project.getUserId());
        device.setAgentId(agentId);
        device.setAutoUpdate(1);
        device.setSort(0);
        device.setCreator(project.getUserId());
        device.setUpdater(project.getUserId());
        device.setCreateDate(now);
        device.setUpdateDate(now);
        device.setLastConnectedAt(now);
        if (report != null) {
            device.setBoard(report.getBoard() == null ? null : report.getBoard().getType());
            device.setAppVersion(report.getApplication() == null ? null : report.getApplication().getVersion());
        }
        deviceDao.insert(device);
        redisUtils.delete(RedisKeys.getAgentDeviceCountById(agentId));
        return device;
    }

    private ResponseStatusException denied(String message) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }
}
