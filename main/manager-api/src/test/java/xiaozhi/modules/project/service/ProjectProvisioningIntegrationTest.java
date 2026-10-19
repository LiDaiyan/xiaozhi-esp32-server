package xiaozhi.modules.project.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.beans.factory.annotation.Autowired;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.apache.ibatis.session.SqlSessionFactory;

import xiaozhi.common.exception.RenException;
import xiaozhi.common.redis.RedisUtils;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.dto.ContextProviderDTO;
import xiaozhi.modules.project.dao.ProjectDao;
import xiaozhi.modules.project.dto.ProjectSaveDTO;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.agent.service.AgentService;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.project.dao.ProjectDeviceDao;

/** Runs only against a disposable database explicitly named project_test_*. */
@SpringJUnitConfig(ProjectProvisioningIntegrationTest.TestConfig.class)
@EnabledIfEnvironmentVariable(named = "PROJECT_TEST_JDBC_URL", matches = ".*project_test_.*")
class ProjectProvisioningIntegrationTest {
    private static final String MAC = "aa:bb:cc:dd:ee:ff";
    @Autowired private ProjectService service;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private ProjectDao projectDao;

    @BeforeEach
    void resetDisposableSchema() {
        jdbc.execute("DROP TABLE IF EXISTS ai_project_device");
        jdbc.execute("DROP TABLE IF EXISTS ai_project");
        jdbc.execute("DROP TABLE IF EXISTS ai_device");
        jdbc.execute("DROP TABLE IF EXISTS ai_agent");
        jdbc.execute("DROP TABLE IF EXISTS sys_user");
        jdbc.execute("CREATE TABLE sys_user(id bigint PRIMARY KEY)");
        jdbc.execute("CREATE TABLE ai_agent(id varchar(32) PRIMARY KEY, user_id bigint, agent_name varchar(64))");
        jdbc.execute("CREATE TABLE ai_device(id varchar(32) PRIMARY KEY, user_id bigint, mac_address varchar(50), "
                + "last_connected_at datetime, auto_update tinyint, board varchar(50), alias varchar(64), agent_id varchar(32), "
                + "app_version varchar(20), sort int, updater bigint, update_date datetime, creator bigint, create_date datetime)");
        // Execute the production migration unchanged, including its foreign keys and indexes.
        new ResourceDatabasePopulator(new ClassPathResource("db/changelog/202610141200.sql"),
                new ClassPathResource("db/changelog/202610151200.sql"),
                new ClassPathResource("db/changelog/202610191200.sql")).execute(dataSource);
        jdbc.update("INSERT INTO sys_user VALUES (7)");
        jdbc.update("INSERT INTO ai_project(project_id, project_name, user_id, enabled, created_at) VALUES ('p1', '工厂一', 7, 1, NOW()), ('p2', '工厂二', 7, 1, NOW())");
        jdbc.update("INSERT INTO ai_project_device VALUES (?, 'p1', NOW())", MAC);
    }

    @Test
    void concurrentFirstBootCreatesExactlyOneAgentAndDevice() throws Exception {
        try (var pool = Executors.newFixedThreadPool(10)) {
            CountDownLatch ready = new CountDownLatch(10);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<DeviceEntity>> requests = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                requests.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return service.provision("p1", MAC, null);
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            String agentId = requests.get(0).get(20, TimeUnit.SECONDS).getAgentId();
            for (Future<DeviceEntity> request : requests) {
                assertEquals(agentId, request.get(20, TimeUnit.SECONDS).getAgentId());
            }
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_agent", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_device", Integer.class));
    }

    @Test
    void failedDeviceInsertRollsBackNewAgent() {
        jdbc.update("INSERT INTO ai_device (id, mac_address) VALUES (?, '11:22:33:44:55:66')", MAC);
        assertThrows(DuplicateKeyException.class, () -> service.provision("p1", MAC, null));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_agent", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_device", Integer.class));
    }

    @Test
    void conflictingImportRollsBackTheEntireBatch() {
        jdbc.update("INSERT INTO ai_project_device VALUES ('bb:bb:bb:bb:bb:bb', 'p2', NOW())");
        assertThrows(RenException.class, () -> service.importDevices("p1",
                List.of("00:11:22:33:44:55", "bb:bb:bb:bb:bb:bb"), 7L));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_project_device WHERE mac_address='00:11:22:33:44:55'", Integer.class));
    }

    @Test
    void projectContextJsonRoundTripsAndSurvivesMetadataUpdateThenClears() {
        ContextProviderDTO provider = new ContextProviderDTO();
        provider.setUrl("https://example.com/context");
        provider.setHeaders(Map.of("Authorization", "Bearer test-value", "X-Project", "p1"));
        service.updateContextProviders("p1", List.of(provider), 7L);
        assertEquals(List.of(provider), service.getContextProviders("p1", 7L));
        assertEquals(List.of(provider), projectDao.lockById("p1").getContextProviders());

        ProjectSaveDTO metadata = new ProjectSaveDTO();
        metadata.setProjectId("p1"); metadata.setProjectName("重命名项目"); metadata.setEnabled(true);
        service.update("p1", metadata, 7L);
        assertEquals(List.of(provider), service.getDefaultContextProviders("p1", 7L));
        assertTrue(service.getDefaultContextProviders("p2", 7L).isEmpty());
        assertTrue(service.getDefaultContextProviders("p1", 8L).isEmpty());

        service.provision("p1", MAC, null);
        assertEquals(List.of(provider), service.getDefaultContextProviders("p1", 7L));
        service.updateContextProviders("p1", List.of(), 7L);
        assertTrue(service.getContextProviders("p1", 7L).isEmpty());
        assertEquals(0, jdbc.queryForObject("SELECT JSON_LENGTH(context_providers) FROM ai_project WHERE project_id='p1'", Integer.class));
    }

    @Test
    void defaultPromptRoundTripsPreservesOnOmittedUpdateAndCanBeCleared() {
        ProjectSaveDTO dto = new ProjectSaveDTO();
        dto.setProjectId("p3"); dto.setProjectName("项目三");
        dto.setDefaultPrompt("你是项目客服。上下文：{{dynamic_context}}");
        service.create(dto, 7L);
        assertEquals(dto.getDefaultPrompt(), projectDao.selectById("p3").getDefaultPrompt());

        String prompt = dto.getDefaultPrompt();
        dto.setDefaultPrompt(null);
        service.update("p3", dto, 7L);
        assertEquals(prompt, projectDao.selectById("p3").getDefaultPrompt());
        dto.setDefaultPrompt("");
        service.update("p3", dto, 7L);
        assertEquals("", projectDao.selectById("p3").getDefaultPrompt());
    }

    @Configuration
    @EnableTransactionManagement
    @MapperScan(basePackageClasses = {ProjectDao.class, DeviceDao.class})
    static class TestConfig {
        @Bean DataSource dataSource() {
            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
            dataSource.setUrl(System.getenv("PROJECT_TEST_JDBC_URL"));
            dataSource.setUsername("root");
            dataSource.setPassword("project-test");
            return dataSource;
        }
        @Bean JdbcTemplate jdbcTemplate(DataSource dataSource) { return new JdbcTemplate(dataSource); }
        @Bean PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(configuration);
            return factory.getObject();
        }
        @Bean AgentDao agentDao(JdbcTemplate jdbc) {
            AgentDao dao = mock(AgentDao.class);
            when(dao.selectById(anyString())).thenAnswer(call -> {
                List<AgentEntity> results = jdbc.query("SELECT * FROM ai_agent WHERE id=?", (row, index) -> {
                    AgentEntity agent = new AgentEntity();
                    agent.setId(row.getString("id")); agent.setUserId(row.getLong("user_id"));
                    agent.setProjectId(row.getString("project_id")); return agent;
                }, call.getArgument(0, String.class));
                return results.isEmpty() ? null : results.get(0);
            });
            return dao;
        }
        @Bean AgentService agentService(JdbcTemplate jdbc) {
            AgentService agents = mock(AgentService.class);
            when(agents.createAgentForProject(anyString(), anyLong(), anyString(), nullable(String.class))).thenAnswer(call -> {
                String id = UUID.randomUUID().toString().replace("-", "");
                jdbc.update("INSERT INTO ai_agent(id, user_id, agent_name, project_id) VALUES (?, ?, ?, ?)",
                        id, call.getArgument(1), call.getArgument(0), call.getArgument(2));
                return id;
            });
            return agents;
        }
        @Bean ProjectService projectService(ProjectDao projects, ProjectDeviceDao whitelist, DeviceDao devices,
                AgentDao agents, AgentService agentService) {
            return new ProjectService(projects, whitelist, devices, agents, agentService, mock(RedisUtils.class));
        }
    }
}
