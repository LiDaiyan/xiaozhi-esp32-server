# 上下文源使用教程

## 概述

`上下文源`，就是为小智系统提示词的上下文添加【数据源】。

`上下文源` 在设备的语音连接初始化时，获取外部系统的数据，并将其动态注入到大模型的系统提示词（System Prompt）中。
让其在连接时读取外部系统的状态。同一个 WebSocket 连接内，后续唤醒或提问不会自动重新请求接口；修改配置后需要设备重新连接。

它和MCP、记忆有本质的区别：`上下文源`是强制让小智感知世界的数据；`记忆(Mem)`是让他知道之前聊了什么内容；`MCP(function_call)`是当需要调用某项能力/知识的时候使用调用。

通过这个功能，在小智唤醒的一刹那，“感知”到：
- 人体健康传感器状态（体温、血压、血氧状态等）
- 业务系统的实时数据（服务器负载、待办数据、股票信息等）
- 任何可以通过 HTTP API 获取的文本信息

**注意**：该功能只是方便小智在唤醒的时候感知事物的状态，而如果想要小智唤醒后实时获取事物的状态，建议在此功能上再结合MCP工具的调用。

## 工作原理

1. **配置源**：用户配置一个或多个 HTTP API 地址。
2. **触发请求**：当系统构建 Prompt 时，如果发现模板中包含 `{{ dynamic_context }}` 占位符，会请求所有配置的 API。
3. **自动注入**：系统会自动将 API 返回的数据格式化为 Markdown 列表，替换 `{{ dynamic_context }}` 占位符。

## 接口规范

为了让小智正确解析数据，您的 API 需要满足以下规范：

- **请求方式**：`GET`
- **请求头**：系统会自动添加 `device-id` 字段到 Request Header。
- **响应格式**：必须返回 JSON 格式，且包含 `code` 和 `data` 字段。

### 响应示例

**情况 1：返回键值对**
```json
{
  "code": 0,
  "msg": "success",
  "data": {
    "客厅温度": "26℃",
    "客厅湿度": "45%",
    "大门状态": "已关闭"
  }
}
```
*注入效果：*
```markdown
<context>
- **客厅温度：** 26℃
- **客厅湿度：** 45%
- **大门状态：** 已关闭
</context>
```

**情况 2：返回列表**
```json
{
  "code": 0,
  "data": [
    "您有10个待办事项",
    "当前汽车的行驶速度是100km每小时"
  ]
}
```
*注入效果：*
```markdown
<context>
- 您有10个待办事项
- 当前汽车的行驶速度是100km每小时
</context>
```

## 配置指南

### 方式 1：智控台配置（全模块部署）

1. 登录智控台，进入**角色配置**页面。
2. 找到**上下文源**配置项（点击“编辑源”按钮）。
3. 点击**添加**，输入您的 API 地址。
4. 如果 API 需要鉴权，可以在**请求头**部分添加 `Authorization` 或其他 Header。
5. 保存配置。

### 按项目统一配置（全模块部署）

1. 首页打开【项目与白名单】，在对应 Project-Id 一行点击【上下文源】。
2. 在编辑源弹窗中添加接口地址和可选请求头，点击【确定】即可保存项目配置。
3. 该项目的设备重新连接后，使用最新配置；之后新加入的白名单设备也会自动使用。

生效顺序：**智能体单独配置非空时优先使用；否则使用所属项目的上下文源；两处均为空时不使用上下文源**。
各项目配置相互独立。智能体中清空上下文源表示恢复继承项目配置；项目中清空表示移除项目默认源。
项目修改不会覆盖智能体已有的单独配置，也不会将项目配置复制到智能体中。

组内设备共享接口配置，每台设备连接时仍独立请求接口，并携带各自的 `device-id`。
返回的数据分别进入各自的对话上下文，不会把某台设备的请求结果共享给整个组。

项目上下文源管理接口需使用项目所属账号的登录 token：

- `GET /xiaozhi/project/{projectId}/context-providers`：读取该项目配置。
- `PUT /xiaozhi/project/{projectId}/context-providers`：保存该项目配置。

保存请求示例：

```json
{
  "contextProviders": [
    {"url": "https://api.example.com/context", "headers": {"X-Project": "factory-a"}}
  ]
}
```

清空使用 `{"contextProviders": []}`。设备侧仍使用原来的 `context_providers` 配置字段，无需修改固件。

### 方式 2：配置文件配置（单模块部署）

编辑 `xiaozhi-server/data/.config.yaml` 文件，添加 `context_providers` 配置段：

```yaml
# 上下文源配置
context_providers:
  - url: "http://api.example.com/data"
    headers:
      Authorization: "Bearer your-token"
  - url: "http://another-api.com/data"
```

## 启用功能

此占位符在基础提示词模板中解析；智控台【角色介绍】里的同名文字不会再次作为模板解析。默认情况下，系统的基础提示词模板文件（默认 `agent-base-prompt.txt`，由 `prompt_template` 指定）中已经预置了 `{{ dynamic_context }}` 占位符，您无需手动添加。

**示例：**

```markdown
<context>
【重要！以下信息已实时提供，无需调用工具查询，请直接使用：】
- **设备ID：** {{device_id}}
- **当前时间：** {{current_time}}
...
{{ dynamic_context }}
</context>
```

**注意**：如果您不需要使用此功能，可以选择**不配置任何上下文源**，也可以从提示词模板文件中**删除** `{{ dynamic_context }}` 占位符。

## 附录：Mock 测试服务示例

为了方便您测试和开发，我们提供了一个简单的 Python Mock Server 脚本。您可以运行此脚本在本地模拟 API 接口。

**mock_api_server.py**

```python
import http.server
import socketserver
import json
from urllib.parse import urlparse, parse_qs

# 设置端口号
PORT = 8081

class MockRequestHandler(http.server.SimpleHTTPRequestHandler):
    def do_GET(self):
        # 解析路径和参数
        parsed_path = urlparse(self.path)
        path = parsed_path.path
        query = parse_qs(parsed_path.query)

        response_data = {}
        status_code = 200

        print(f"收到请求: {path}, 参数: {query}")

        # Case 1: 模拟健康数据 (返回字典 Dict)
        # 路径参数风格: /health
        # device_id 从 Header 获取
        if path == "/health":
            device_id = self.headers.get("device-id", "unknown_device")
            print(f"device_id: {device_id}")
            response_data = {
                "code": 0,
                "msg": "success",
                "data": {
                    "测试设备ID": device_id,
                    "心率": "80 bpm",
                    "血压": "120/80 mmHg",
                    "状态": "良好"
                }
            }

        # Case 2: 模拟新闻列表 (返回列表 List)
        # 无参数: /news/list
        elif path == "/news/list":
            response_data = {
                "code": 0,
                "msg": "success",
                "data": [
                    "今日头条：Python 3.14 发布",
                    "科技新闻：AI 助手改变生活",
                    "本地新闻：明日有大雨，记得带伞"
                ]
            }

        # Case 3: 模拟天气简报 (返回字符串 String)
        # 无参数: /weather/simple
        elif path == "/weather/simple":
            response_data = {
                "code": 0,
                "msg": "success",
                "data": "今日晴转多云，气温 20-25 度，空气质量优，适合出行。"
            }

        # Case 4: 模拟设备详情 (Query参数风格)
        # 参数风格: /device/info
        # device_id 从 Header 获取
        elif path == "/device/info":
            device_id = self.headers.get("device-id", "unknown_device")
            response_data = {
                "code": 0,
                "msg": "success",
                "data": {
                    "查询方式": "Header参数",
                    "设备ID": device_id,
                    "电量": "85%",
                    "固件": "v2.0.1"
                }
            }
        
        # Case 5: 404 Not Found
        else:
            status_code = 404
            response_data = {"error": "接口不存在"}

        # 发送响应
        self.send_response(status_code)
        self.send_header('Content-type', 'application/json; charset=utf-8')
        self.end_headers()
        self.wfile.write(json.dumps(response_data, ensure_ascii=False).encode('utf-8'))

# 启动服务
# 允许地址重用，防止快速重启报错
socketserver.TCPServer.allow_reuse_address = True
with socketserver.TCPServer(("", PORT), MockRequestHandler) as httpd:
    print(f"==================================================")
    print(f"Mock API Server 已启动: http://localhost:{PORT}")
    print(f"可用接口列表:")
    print(f"1. [字典] http://localhost:{PORT}/health")
    print(f"2. [列表] http://localhost:{PORT}/news/list")
    print(f"3. [文本] http://localhost:{PORT}/weather/simple")
    print(f"4. [参数] http://localhost:{PORT}/device/info")
    print(f"==================================================")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n服务已停止")
```
