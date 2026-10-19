ALTER TABLE ai_project
    ADD COLUMN default_prompt TEXT DEFAULT NULL COMMENT '白名单设备自动创建智能体时使用的默认提示词';
