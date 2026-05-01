# PC 端远程服务（Flask）

与 Android 原型配合：手机在「单次高置信识别触发」后向本机发送 HTTP 请求。

- **模式1**：`POST /command` 注入键盘（PPT 翻页、显示桌面）。
- **模式2**：`POST /llm` 将英文空间说明与用户文本拼成 prompt，调用本机 **Ollama** 生成回复。

## 环境

- Windows 10/11（默认按键绑定针对 Windows）
- Python 3.10+
- 模式2 需本机已安装并运行 [Ollama](https://ollama.com/)，且已拉取模型（默认 `llama3.2`，可用环境变量覆盖）

## 安装

```bash
cd pc-remote
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
```

## 运行

```bash
set REMOTE_TOKEN=your_shared_secret
set OLLAMA_HOST=http://127.0.0.1:11434
set OLLAMA_MODEL=llama3.2
python app.py
```

不设 `REMOTE_TOKEN` 时，`/command` 与 `/llm` 不对局域网鉴权（仅适合可信网络演示）。

默认监听 `0.0.0.0:5000`。手机 Base URL 示例：`http://192.168.1.23:5000`。

### 查本机 IP

PowerShell：`ipconfig`，取当前网卡 IPv4。

### 防火墙

允许 Python 或端口 `5000` 在**专用网络**入站。

### 权限说明

`pynput` 在部分环境需**管理员身份**运行终端后再执行 `python app.py`。

## API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/health` | 健康检查，**不**校验 Token |
| POST | `/command` | JSON：`{"action":"ppt_previous"\|"ppt_next"\|"show_desktop"}` |
| POST | `/llm` | JSON：`{"spatial_sector":"TOP"\|"RIGHT"\|"BOTTOM", "user_text":"..."}` |

若设置了 `REMOTE_TOKEN`，`/command` 与 `/llm` 须带请求头：`X-Auth-Token: <与手机一致>`。

`/llm` 在服务端将 `spatial_sector` 映射为英文空间说明，再与用户文本一并送入 Ollama；成功时返回 `{"ok":true,"response":"..."}`，失败可能返回 `502` 及 `error` 字段。

## 按键映射（仅 /command）

| action | 行为 |
|--------|------|
| `ppt_previous` | Page Up（上一页） |
| `ppt_next` | Page Down（下一页） |
| `show_desktop` | Win+D |

**注意**：翻页通常需 PowerPoint **放映窗口在前台**；`Win+D` 为系统级快捷键。

## Android 模式1 固定扇区映射（由应用内开关选模式1）

| 扇区 | `action` |
|------|-----------|
| Top | `ppt_previous` |
| Right | `show_desktop` |
| Bottom | `ppt_next` |

模式2 不调用 `/command`，而在用户补充文本后调用 `/llm`。
