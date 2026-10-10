# product 分支部署

安装 Docker 和 Docker Compose v2，以下命令在仓库根目录执行。
语音服务、管理前后端均从当前仓库源码构建。

## 启动

```bash
docker compose -f deploy/compose.yml up -d --build
```

初始化服务会自动取得 `server.secret` 并写入 `.config.yaml`，完成后启动语音服务。
已有配置会保留，同步管理端地址和密钥，并补齐缺失的内部 HTTP 端口。
无需手动复制配置或填写 secret。

访问 `http://服务器地址:8002`，注册第一个账号作为管理员。
在【模型配置】中填写百炼 ASR、LLM、TTS 的 API Key，选择模型和音色，
将默认 ASR 设置为百炼 Paraformer，并在智能体模板及智能体中选用云端模型。
保存后，在【服务端管理】中点击【更新配置】；如果语音服务此前因缺少本地模型启动失败，
执行 `docker compose -f deploy/compose.yml restart xiaozhi-esp32-server`。
设备重新连接时会读取智能体的模型配置，无需为此重启服务。

在管理端参数中设置设备可访问的 OTA 和 WebSocket 地址：

- OTA：`http://服务器地址:8002/xiaozhi/ota/`
- WebSocket：`ws://服务器地址:8000/xiaozhi/v1/`

使用反代时填写实际 HTTPS/WSS 地址。

## 修改源码后重新部署

```bash
docker compose -f deploy/compose.yml up -d --build
```

如果修改了 Python 的 `requirements.txt`，先重建依赖基础镜像：

```bash
docker build -f Dockerfile-server-base -t ghcr.io/xinnan-tech/xiaozhi-esp32-server:server-base .
docker compose -f deploy/compose.yml up -d --build xiaozhi-esp32-server
```

## 查看日志

```bash
docker compose -f deploy/compose.yml logs --tail=100 -f xiaozhi-esp32-server xiaozhi-esp32-server-web
```

已有旧部署时，先停止旧服务以释放端口。复用已有数据库时，
Compose 中的密码须与数据库实际密码一致，并使用相同版本的 MySQL 镜像。
