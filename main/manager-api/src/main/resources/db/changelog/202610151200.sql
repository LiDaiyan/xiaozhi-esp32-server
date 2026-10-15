ALTER TABLE ai_project
    ADD COLUMN context_providers JSON DEFAULT NULL COMMENT '项目默认上下文源，智能体未单独配置时使用';
