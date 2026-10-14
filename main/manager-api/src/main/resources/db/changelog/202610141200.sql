CREATE TABLE ai_project (
    project_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    project_name varchar(100) NOT NULL,
    user_id bigint NOT NULL,
    enabled tinyint NOT NULL DEFAULT 1,
    created_at datetime NOT NULL,
    PRIMARY KEY (project_id),
    KEY idx_project_user (user_id),
    CONSTRAINT fk_project_user FOREIGN KEY (user_id) REFERENCES sys_user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='设备项目';

CREATE TABLE ai_project_device (
    mac_address varchar(17) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    project_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at datetime NOT NULL,
    PRIMARY KEY (mac_address),
    KEY idx_project_device_project (project_id),
    CONSTRAINT fk_project_device_project FOREIGN KEY (project_id) REFERENCES ai_project(project_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='项目设备白名单，一台设备属于一个项目';

ALTER TABLE ai_agent
    ADD COLUMN project_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '所属项目',
    ADD KEY idx_agent_user_project (user_id, project_id);
