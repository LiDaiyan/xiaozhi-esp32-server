# 按 Project-Id 自动绑定设备

每个项目维护一个 MAC 白名单。设备首次请求 OTA 时，服务端用请求头中的
`Project-Id` 和 `Device-Id` 检查白名单，为设备创建一个智能体并绑定，响应不包含六位激活码。
再次请求时复用原智能体。`Device-Id` 是设备 MAC，不使用请求体中的 `mac_address` 作为鉴权依据。

## 智控台操作

1. 在【智能体模板】中设置默认模板，选择已配置的云端 ASR、LLM、TTS、音色和角色提示词。
2. 首页点击【项目与白名单】，填写 Project-Id 和项目名称并创建项目。
3. 选择该项目的【白名单】，粘贴 MAC 地址，或导入 `.txt` / `.csv` 文件，然后点击【追加到白名单】。
4. 给设备固件设置相同的 Project-Id。设备开机请求 OTA 后，首页点击【刷新】，可以看到按项目分组的智能体。

项目及自动创建的智能体归属于创建项目的智控台账号。账号只能管理自己的项目和白名单。
Project-Id 全局唯一、区分大小写，支持字母、数字、下划线和连字符，最多 64 位，创建后不能修改。
一台设备只能属于一个项目；导入支持重复项、大小写及冒号/连字符归一化，每批最多 5000 条。
文件只包含 MAC 地址，不包含表头。任一条地址非法或属于其他项目时，整个导入批次回滚。

已经手动绑定到旧智能体的设备，需要先在设备管理页面解绑，再加入项目白名单。
项目智能体仍可单独配置模型、音色和提示词；之后修改默认模板不会覆盖已有智能体。

## 设备端请求

在原有 OTA POST 请求中增加 `Project-Id` 请求头，原来的请求体、Device-Id、Client-Id 保持原有协议。
如果固件还会调用 `/xiaozhi/ota/activate`，该请求也要带 Project-Id。

```http
POST /xiaozhi/ota/ HTTP/1.1
Host: 服务器地址:8002
Content-Type: application/json
Project-Id: factory-a
Device-Id: aa:bb:cc:dd:ee:ff
Client-Id: 设备原有的客户端ID

{"application":{"version":"1.0.0"},"board":{"type":"你的设备型号"}}
```

项目不存在、项目停用、MAC 不在该项目白名单中，返回 HTTP 403，不返回 WebSocket 地址、token 或激活码。
已有设备属于其他智能体或账号时返回 HTTP 409，不会自动迁移绑定。
已纳入项目的设备缺少 Project-Id 也会被拒绝。
未纳入任何项目的旧设备仍使用原来的验证码绑定流程，首页显示在【未分组】。

成功后设备照常使用 OTA 响应中的 WebSocket 地址和 token 连接语音服务；
WebSocket 请求无需新增 Project-Id。原有的 `server.auth.enabled` 和 token 鉴权机制继续生效。
这套白名单按 MAC 判断设备资格，不防止他人冒用已获准的 MAC。

停用项目或移除 MAC 会拒绝后续 OTA 和语音配置获取，保留智能体与历史记录。
已建立的对话不会被立即中断。重新启用或恢复白名单后复用原绑定。
如果删除智能体（原有逻辑同时删除关联设备）或解绑设备，下一次合法 OTA 会重新创建智能体。

## 管理 API

以下接口位于 `/xiaozhi` 下，均需智控台登录 token，权限限制到当前账号：

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/project` | 列出项目 |
| POST | `/project` | 创建项目 |
| PUT | `/project/{projectId}` | 修改名称或接入开关 |
| GET | `/project/{projectId}/devices` | 查看 MAC 白名单 |
| POST | `/project/{projectId}/devices` | 追加一批 MAC |
| DELETE | `/project/{projectId}/devices/{macAddress}` | 移除 MAC |

创建/修改项目的 JSON：

```json
{"projectId":"factory-a","projectName":"项目 A","enabled":true}
```

追加白名单的 JSON：

```json
{"macAddresses":["aa:bb:cc:dd:ee:ff","11:22:33:44:55:66"]}
```

已有 `/agent/list` 及智能体详情响应增加 `projectId`，旧智能体该字段为空。

## 部署与迁移

```bash
docker compose -f deploy/compose.yml up -d --build xiaozhi-esp32-server-web
```

管理后端启动时通过 Liquibase 自动创建项目和白名单表，并为智能体增加 project_id 字段。
保留数据库和挂载目录即可保留项目、白名单、设备绑定和智能体配置。

发布后检查首页项目入口，导入一个测试 MAC，并验证合法 OTA 不含 activation、
错误项目/非白名单 MAC 返回 403，再重复请求确认仍为同一智能体。

需要回退应用代码时，保留新增表和字段即可运行此前版本；此前版本不会执行项目白名单检查。
已自动绑定的设备及智能体数据会保留。不要直接删除新增表或字段来回退代码。

## 针对本功能的测试

后端单元测试：

```bash
cd main/manager-api
mvn -DskipTests=false -Dtest=ProjectServiceTest,OTAProjectControllerTest,AgentProjectCreationTest,ConfigServiceImplTest test
```

`ProjectProvisioningIntegrationTest` 检查真实 MySQL 迁移、10 个并发首次连接和失败回滚。
它仅在设置 `PROJECT_TEST_JDBC_URL` 且数据库名包含 `project_test_` 时启用；
需使用独立的临时数据库，root 密码为 `project-test`。测试会重建该临时数据库中的测试表。

前端分组测试：

```bash
node --test main/manager-web/tests/projectGrouping.test.mjs
```
