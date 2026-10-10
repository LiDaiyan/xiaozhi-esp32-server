"""Read the management server secret and prepare the voice server configuration."""

import json
import os
from pathlib import Path
import tempfile
import time
from urllib.error import URLError
from urllib.request import Request, build_opener, ProxyHandler

import pymysql
from ruamel.yaml import YAML


def read_secret():
    with pymysql.connect(
        host=os.environ["MYSQL_HOST"],
        user="root",
        password=os.environ["MYSQL_ROOT_PASSWORD"],
        database="xiaozhi_esp32_server",
        charset="utf8mb4",
        connect_timeout=5,
        read_timeout=5,
        write_timeout=5,
    ) as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                "SELECT param_value FROM sys_params WHERE param_code = %s",
                ("server.secret",),
            )
            row = cursor.fetchone()
    secret = str(row[0]).strip() if row and row[0] is not None else ""
    return secret if secret and secret.lower() != "null" else None


def api_ready(url, secret):
    request = Request(
        url.rstrip("/") + "/config/server-base",
        data=b"{}",
        headers={
            "Content-Type": "application/json",
            "Authorization": "Bearer " + secret,
        },
        method="POST",
    )
    # Management traffic stays inside the Compose network.
    with build_opener(ProxyHandler({})).open(request, timeout=5) as response:
        result = json.load(response)
    return isinstance(result, dict) and result.get("code") == 0


def wait_for_secret(url, timeout=300):
    deadline = time.monotonic() + timeout
    print("等待管理端初始化并生成 server.secret……", flush=True)
    while time.monotonic() < deadline:
        try:
            secret = read_secret()
            if secret and api_ready(url, secret):
                return secret
        except (pymysql.MySQLError, URLError, TimeoutError, OSError, ValueError):
            pass
        time.sleep(2)
    raise TimeoutError("管理端未在规定时间内就绪，请检查 web 和 db 日志及数据库密码")


def write_config(path, template, url, secret):
    yaml = YAML()
    yaml.preserve_quotes = True
    source = path if path.exists() else template
    with source.open(encoding="utf-8") as stream:
        config = yaml.load(stream)
    if not isinstance(config, dict):
        raise ValueError("配置必须为 YAML 映射，已保留原文件")
    server = config.get("server")
    if isinstance(server, dict):
        server.setdefault("http_port", 8003)
    manager = config.setdefault("manager-api", {})
    if not isinstance(manager, dict):
        raise ValueError("manager-api 必须为 YAML 映射，已保留原文件")
    manager["url"] = url
    manager["secret"] = secret
    path.parent.mkdir(parents=True, exist_ok=True)
    # Write atomically so the voice server cannot read a partial configuration.
    temp_path = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w", encoding="utf-8", dir=path.parent, delete=False
        ) as stream:
            temp_path = Path(stream.name)
            yaml.dump(config, stream)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temp_path, path)
    finally:
        if temp_path is not None:
            temp_path.unlink(missing_ok=True)


def main():
    url = os.environ["MANAGER_API_URL"]
    secret = wait_for_secret(url)
    write_config(
        Path("/data/.config.yaml"), Path("/app/config.example.yaml"), url, secret
    )
    print("语音服务配置已就绪，server.secret 已自动同步", flush=True)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # Error details can include SQL or credentials; do not print them.
        print(f"初始化失败（{type(error).__name__}），请检查配置和管理端状态", flush=True)
        raise SystemExit(1)
