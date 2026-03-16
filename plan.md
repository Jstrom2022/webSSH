**原因分析：**
1. **底层的 OS 工作目录未更改**：在 `AiCliExecutor.java` 中构建 `ProcessBuilder` 时，虽然使用参数 `cmd.add("-C")` 传递了目录，但并未调用 `ProcessBuilder.directory(File)`。这导致 Codex 进程本身及其衍生出的子工具（如 bash）默认继承主进程（WebSSH 后端，即 `/Users/jin/Downloads/git/vpsSSH`）的工作目录。
2. **Telegram 消息入口的硬编码**：在 `TelegramBotProvider.java` 的 `handleAiCli` 方法中，存在逻辑：当 SSH 已连接时，设 `workDir = null`；未连接时设为 `/tmp`。这意味着在连接 SSH 后，无论如何也不会传递 `-C` 给 Codex。
3. **命令解析不支持参数**：如果用户尝试在 Telegram 输入 `/codex -C /home/data 分析脚本`，代码直接将其截断分为指令 `/codex` 与完整的提示词 `"-C /home/data 分析脚本"`。后者被作为一个整体字符串传给了代理模型，Codex 会将其全部当做指令文本，并不理解这是命令行工作目录。

**构思方案 (Implementation Plan)：**
1. **完善底层进程目录隔离 (AiCliExecutor.java)**：如果 `workDir` 存在且有效，自动将其设置为 `ProcessBuilder` 的启动目录，从系统级将运行时上下文锁定在指定目录。
2. **支持终端灵活传参 (TelegramBotProvider.java)**：增强参数解析能力，支持用户在提示词前传入 `-C <目录>` 或 `--cwd <目录>` 显式指定工作目录（例如：`/codex -C /var/log 查看错误日志`）。从 prompt 中截取目录并作为 `workDir` 入参。

**具体任务清单 (Task List)：**
- [ ] 任务 1：修改 `AiCliExecutor.java`，增加对 `ProcessBuilder.directory()` 的设置验证。
- [ ] 任务 2：修改 `TelegramBotProvider.java` 的 `handleAiCli` 方法，运用正则或字符串匹配从 prompt 开头拦截并解析出 `-C <path>` 的信息，传入 `workDir`。

