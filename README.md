# WebSSH（Spring Boot）

基于 Java Spring Boot + WebSocket 的浏览器 SSH 工具，支持多标签终端、会话保存、SFTP 文件管理、端口转发、主机指纹校验、移动端适配、国际化和登录鉴权。

## 功能

- **登录鉴权** — Spring Security 表单登录，内存用户存储（BCrypt 加密）
- **多标签 SSH 终端** — 每个标签独立 WebSocket + xterm.js，互不干扰
- **会话保存** — 按登录用户持久化到本地 JSON 文件
- **凭据加密保存** — AES-GCM 加密，主密钥可配置
- **主机指纹校验** — SHA-256，首次连接自动信任并回填
- **认证方式** — 密码认证、私钥认证（私钥口令可选）
- **终端尺寸同步** — 浏览器窗口变化自动同步到远端 PTY
- **SFTP 文件管理** — 目录浏览、上传（分片）、下载（分片 + ACK 流控）、创建目录
- **SSH 端口转发** — 本地转发（L）/ 远程转发（R）
- **Shell 工作目录追踪** — 注入 shell 钩子实时感知远端 `$PWD` 变化，SFTP 面板自动同步
- **终端主题** — 6 种配色方案（默认蓝、橙、绿、琥珀、紫、红）
- **国际化** — 支持 7 种语言（简体中文、English、日本語、한국어、Deutsch、Français、Русский）
- **全屏模式** — 终端可切换全屏显示
- **移动端适配** — 响应式 Web 设计，针对手机端优化布局、侧边栏滑动及文件管理交互
- **聊天机器人接入** — 支持 Telegram Bot、微信ClawBot 与 QQ 私聊机器人，可直接通过消息管理 SSH 与 AI 编程任务

## 技术栈

| 层级 | 技术 |
|------|------|
| 后端框架 | Java 17 + Spring Boot 3.3 |
| Web 通信 | Spring WebSocket（`/ws/ssh`） |
| 安全 | Spring Security（表单登录 + BCrypt） |
| SSH 客户端 | JSch（`com.github.mwiede:jsch:0.2.19`） |
| 前端终端 | xterm.js + xterm-addon-fit |
| 构建工具 | Maven |

前端终端依赖以静态资源方式内置：

- `/vendor/xterm/xterm.js` + `xterm.css`
- `/vendor/xterm-addon-fit/xterm-addon-fit.js`

## 项目结构

```
├── build.sh                          # 构建打包脚本
├── start.sh                          # 启动/停止/重启管理脚本
├── pom.xml                           # Maven 配置
└── src/main/
    ├── java/com/webssh/
    │   ├── WebSshApplication.java    # 启动入口
    │   ├── config/                   # 配置类（Security、WebSocket、属性绑定）
    │   ├── session/                  # 会话持久化与凭据加密
    │   ├── web/                      # REST 控制器（认证、会话 CRUD、页面路由）
    │   └── ws/                       # WebSocket 核心处理器（SSH/SFTP/端口转发）
    └── resources/
        ├── application.properties    # 应用配置
        └── static/                   # 前端静态资源
            ├── index.html            # 主页面（终端 UI）
            ├── login.html            # 登录页
            ├── app.js                # 前端主逻辑
            ├── i18n.js               # 国际化翻译
            ├── style.css             # 主样式
            └── login.css             # 登录页样式
```

## 默认配置

配置文件：[application.properties](src/main/resources/application.properties)

### 基础配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `webssh.auth.username` | `admin` | 登录用户名 |
| `webssh.auth.password` | `admin123` | 登录密码 |
| `webssh.session-store.directory` | `./data/sessions` | 会话数据存储目录 |
| `webssh.crypto.master-key` | `change-this-master-key-in-production` | 凭据加密主密钥 |
| `webssh.ssh.allow-legacy-ssh-rsa` | `true` | 是否允许旧版 ssh-rsa 算法 |
| `webssh.ssh.server-alive-interval-ms` | `30000` | SSH Keepalive 间隔（毫秒） |
| `server.port` | `8080` | 服务端口 |

### 资源治理配置

这些配置用于限制系统资源消耗，防止单个用户或机器人任务占满系统资源。

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `webssh.resource.shell-output.max-size` | `128` | 最大并发 Shell 输出任务数 |
| `webssh.resource.bot-command.max-size` | `16` | 最大并发机器人命令任务数 |
| `webssh.resource.ai-task.max-size` | `8` | 最大并发 AI 编程任务数 |
| `webssh.resource.ws-shell-per-user` | `6` | 每用户最大并发 SSH 标签页 |
| `webssh.resource.bot-command-per-user` | `2` | 每用户最大并发机器人指令 |
| `webssh.resource.ai-task-per-user` | `1` | 每用户最大并发 AI 任务 |
| `webssh.resource.bot-command-timeout` | `30s` | 机器人普通命令执行超时 |
| `webssh.resource.ai-task-timeout` | `15m` | AI 编程任务总执行超时 |

> ⚠️ 生产环境务必通过环境变量或外部配置覆盖默认账户密码和加密主密钥。

## 快速开始

### 开发模式

```bash
# 1. 克隆项目
git clone https://github.com/Jstrom2022/webSSH.git && cd webSSH

# 2. 启动应用（需要 Java 17+ 和 Maven）
mvn spring-boot:run

# 3. 打开浏览器访问
# http://localhost:8080
# 默认账户：admin / admin123
```

### 构建打包

```bash
./build.sh
```

执行后会在 `release/` 目录生成部署文件：

```
release/
├── webssh.jar                    # 可执行 JAR
├── start.sh                      # 启动管理脚本
└── config/application.properties # 外部配置（可修改）
```

### 服务器部署

将 `release/` 目录上传到服务器，使用启动脚本管理：

```bash
./start.sh start     # 启动
./start.sh stop      # 停止
./start.sh restart   # 重启
./start.sh status    # 查看状态
```

默认 JVM 参数：`-Xms128m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200`

可通过 `JAVA_OPTS` 环境变量覆盖：

```bash
JAVA_OPTS="-Xms256m -Xmx1g" ./start.sh start
```

### Docker 部署

> 需要 Docker 20.10+ 以及 Docker Compose v2（`docker compose` 命令）。

**一键启动**

```bash
# 克隆项目
git clone https://github.com/Jstrom2022/webSSH.git && cd webSSH

# 后台构建并启动（首次构建需要几分钟）
docker compose up -d --build

# 访问 http://localhost:8080
```

**环境变量配置（推荐）**

在项目根目录创建 `.env` 文件，覆盖默认的敏感配置：

```env
WEBSSH_AUTH_USERNAME=admin
WEBSSH_AUTH_PASSWORD=your-strong-password
WEBSSH_CRYPTO_MASTER_KEY=your-random-secret-key
```

> ⚠️ 生产环境务必修改以上三项，`.env` 文件已在 `.gitignore` 中排除，不会提交到仓库。

**数据持久化**

会话数据挂载到宿主机的 `./data/` 目录，容器重建后数据不丢失。

**常用命令**

```bash
docker compose up -d          # 后台启动
docker compose down           # 停止并删除容器
docker compose logs -f        # 实时查看日志
docker compose restart        # 重启服务
docker compose ps             # 查看运行状态
```

## 兼容旧 SSH 服务器（ssh-rsa）

如果连接时报错：

> `Algorithm negotiation fail ... algorithmName="server_host_key" ... serverProposal="ssh-rsa"`

说明目标服务器只提供 `ssh-rsa` 主机密钥。当前默认已开启兼容：

```properties
webssh.ssh.allow-legacy-ssh-rsa=true
```

建议优先升级服务器到 `ssh-ed25519` 或 `rsa-sha2-*`，然后关闭该开关以提高安全性。

## 主机指纹流程

1. 首次连接某主机时，若 `hostFingerprint` 为空，服务端自动信任当前指纹并继续连接
2. 连接成功后服务端将实际指纹回传给前端，前端自动回填到会话配置
3. 后续连接按该指纹严格校验；若不一致则拒绝连接

## 会话保存与凭据加密

- **会话 API**：`GET /api/sessions` · `GET /api/sessions/{id}` · `POST /api/sessions` · `DELETE /api/sessions/{id}`
- **保存内容**：会话名、主机、端口、用户名、认证方式、主机指纹
- 可选勾选「保存加密凭据（密码/私钥）」
  - 勾选后使用 `AES/GCM/NoPadding` 加密凭据后落盘；加载时解密回填
  - 不勾选则不保存密码/私钥
- 列表接口仅返回 `hasSavedCredentials` 元信息，不暴露实际凭据明文

## WebSocket 协议

端点：`/ws/ssh`

### 客户端 → 服务端

| 消息类型 | 说明 |
|----------|------|
| `connect` | 建立 SSH 连接 |
| `input` | 发送终端输入 |
| `resize` | 同步终端大小 |
| `disconnect` | 断开 SSH 连接 |
| `sftp_list` | 读取目录列表 |
| `sftp_mkdir` | 创建目录 |
| `sftp_upload_start` | 开始分片上传 |
| `sftp_upload_chunk` | 上传分片数据（Base64） |
| `sftp_upload` | 兼容旧版的一次性上传 |
| `sftp_download` | 下载文件 |
| `sftp_download_ack` | 下载分片确认 |
| `port_forward_add` | 新增端口转发 |
| `port_forward_remove` | 删除端口转发 |
| `port_forward_list` | 读取当前转发列表 |

### 服务端 → 客户端

| 消息类型 | 说明 |
|----------|------|
| `info` | 信息提示 |
| `hostkey_required` | 需要确认主机密钥 |
| `connected` | SSH 连接成功 |
| `output` | 终端输出（Base64 编码） |
| `sftp_list` | 目录列表结果 |
| `sftp_upload` | 上传结果 |
| `sftp_download_start` | 下载开始（含文件大小） |
| `sftp_download_chunk` | 下载分片数据 |
| `sftp_download` | 兼容旧版下载结果 |
| `port_forward_list` | 端口转发列表 |
| `error` | 错误信息 |
| `disconnected` | SSH 连接已断开 |

## REST API

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/auth/me` | 获取当前登录用户信息 |
| `GET` | `/api/sessions` | 列出当前用户的所有会话 |
| `GET` | `/api/sessions/{id}` | 获取会话详情（含解密凭据） |
| `POST` | `/api/sessions` | 新增或更新会话 |
| `DELETE` | `/api/sessions/{id}` | 删除会话 |

## 机器人能力

### 支持的机器人类型

- **Telegram Bot**：使用 Long Polling，无需公网回调
- **QQ 私聊机器人**：按 OpenClaw 同款方式直连官方 OpenAPI + Gateway，当前支持与机器人私聊触发
- **微信 ClawBot**：通过微信官方 iLink 协议直连，支持扫码登录，无需公网 IP。

### 机器人命令

几种机器人共用同一套核心命令：

- SSH：`/list`、`/connect`、`/disconnect`、`/status`
- AI：`/codex [提示词]`、`/codex_stop`、`/codex_status`、`/codex_clear`
- AI：`/claude [提示词]`、`/claude_stop`、`/claude_status`、`/claude_clear`

`/codex` 或 `/claude` 会进入对应 AI 模式；进入后普通文本将持续按该模式执行，直到输入 `codex_stop` / `codex_clear` / `claude_stop` / `claude_clear`（支持带或不带 `/`）退出模式。未进入 AI 模式时，连接 SSH 后普通文本按 Shell 命令执行。

### 接入说明

#### 1. Telegram 机器人

1. 在 Telegram 中搜索 `@BotFather`。
2. 发送 `/newbot` 命令，按照提示设置机器人的显示名称（Name） and 唯一用户名（Username）。
3. 申请成功后获取 **API Token**。
4. 将 Token 填写到 WebSSH 管理面板的 Telegram 配置项中。

#### 2. QQ 官方机器人

1. 登录 [QQ 开放平台](https://q.qq.com/qqbot/openclaw/index.html)。
2. 申请并获取 **AppID** 和 **AppSecret**。
3. 在管理面板填写信息，启用后通过私聊与机器人交互。

#### 3. 微信 ClawBot

这种方式通过微信官方 iLink 平台接入，流程简洁。

1. 在 WebSSH 管理面板中找到 **微信 ClawBot** 卡片。
2. 点击 **扫码获取 Token**。
3. 弹出二维码后，使用微信扫码并确认（如果是首次使用，请按微信提示操作，通常需要扫码两次：一次建立连接，一次确认授权）。
4. 扫码确认后，系统会自动捕获 `Bot Token` 并回填到表单。
5. 勾选“启用 Bot”并点击“保存并应用”。

### 提示

- **用户隔离**：建议在“允许的用户 ID”中配置你的私人账号 ID，防止机器人被他人误用。
- **关联用户**：每个机器人配置项中都有“关联 WebSSH 用户”，该机器人将能够访问并管理该用户下的所有 SSH 会话。
- **聚合回复**：由于 QQ 和微信平台对消息频率有严格限制，机器人的 SSH 或 AI 输出可能会进行聚合发送，以避免触发频控。


## SFTP 说明

- 上传采用分片模式，前端分片后逐块发送，无固定大小上限
- 下载采用分片 + ACK 流控机制，每块 128KB，避免浏览器内存溢出
- 超大文件仍受浏览器内存与网络稳定性影响
- SFTP 操作在独立 IO 线程池中异步执行，不阻塞 WebSocket 消息处理

## 架构要点

- 每个 WebSocket 会话对应一个 `ClientConnection`，存储 SSH 会话、通道和所有运行状态
- IO 密集型操作（SFTP）提交到独立线程池（核心线程数 ≥ 4，队列容量 256），避免阻塞 WebSocket 线程
- 最大并发 WebSocket 连接数：200
- WebSocket 单条消息上限：8MB
- WebSocket 会话空闲超时：30 分钟

## 开源协议

本项目采用 **GNU Lesser General Public License v3.0 (LGPL-3.0)** 协议开源。详情请参阅 [LICENSE](LICENSE) 和 [COPYING](COPYING) 文件。
