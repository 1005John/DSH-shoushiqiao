# DSH 手势桥（dsh-go-bridge）

把华为智能眼镜变成 **DSH Go 的免手操作入口**：

```
双击镜腿 → 说话 → 再双击 → 自动发送给 DSH Go → Agent 干活 → 结果念给你听
```

全程不碰手机，**锁屏、黑屏状态下同样可用**。

---

## 它解决什么问题

DSH Go（[ccch1mneyyy/dsh-Go](https://github.com/ccch1mneyyy/dsh-Go)）很好用，但每次都要掏手机、解锁、点开 App、按住说话。

戴上眼镜时这是不可能的。本项目用一个常驻前台服务把这条路径自动化：

| 你在做什么 | 系统在做什么 |
|---|---|
| 双击镜腿 | 收到 AVRCP 媒体键，开始连续录音 |
| 说话 | 边说边点亮屏幕、自动划开锁屏（预解锁） |
| 再双击 | 停止录音 → 云端 ASR → 写入 DSH Go → 点发送 |
| 等待 | 轮询界面，等 Agent 生成结束 |
| 听 | 抓取回复 → 总结成 120 字以内 → 本地 TTS 播报 → 自动息屏 |

---

## 三个组成部分

| 模块 | 位置 | 作用 |
|---|---|---|
| **Android 桥接 App** | `android/bridge/` | 手势、录音、无障碍操作、TTS、待办、同步 |
| **PC 文件服务** | `pc/` | 让手机读写 PC 上的文件（局域网优先，公网兜底） |
| **云端只读转发** | `cloud/` | MOA 聊天记录的只读 API（VPS + Caddy） |

---

## 快速开始

### 1. 构建 Android App

本项目**不含构建脚本**，因为 App 是在手机上由 DSH Go 自己的 Agent 编译的（见手册第四节）。
你也可以用标准 Android 工具链编译：需要 `javac + d8 + aapt2`，无第三方依赖。

### 2. 填密钥

代码里所有密钥都是占位符，必须替换成你自己的：

| 占位符 | 出现在 | 用途 |
|---|---|---|
| `YOUR_LLM_ROUTER_TOKEN` | `BridgeService.java` `Narrator.java` `TodoExtractor.java` | 内网 OpenAI 兼容路由（ASR / TTS / 待办提炼 / 回复总结） |
| `YOUR_MOA_READ_TOKEN` | `MoaReader.java` | 云端 MOA 只读 API |
| `YOUR_PC_FILE_TOKEN` / `YOUR_PC_AGENT_TOKEN` | `pc/pc_config.json`、`phone/pc.sh` | PC 文件服务 / Agent 服务 |
| `YOUR_MOA_PUSH_SECRET` | `cloud/` 接收端 | PC → 云端推送 MOA 消息 |

> 桌面端配置：复制 `pc/pc_config.example.json` 为 `pc/pc_config.json` 再改。

### 3. 部署 PC 服务（可选）

```powershell
# 依赖：Node 22+、OpenSSH 客户端
Start-Process node -ArgumentList 'pc_file_server.js','--config','pc_config.json' -WindowStyle Hidden
Start-Process node -ArgumentList 'pc_agent_server.js','--config','pc_config.json' -WindowStyle Hidden

# 开机自启 + 守护（改一下脚本里的路径）
schtasks /Create /TN "DSH-PC-Services" /TR "wscript.exe //nologo D:\path\to\pc_services.vbs" /SC ONLOGON /RL HIGHEST
schtasks /Create /TN "DSH-PC-Watchdog" /TR "powershell -File D:\path\to\pc_watchdog.ps1" /SC MINUTE /MO 1
```

### 4. 手机端授权

安装后需要：

1. 授予 **录音 / 日历 / 通知** 权限
2. 开启 **无障碍服务**（`ClickA11yService`）
3. 授予 **悬浮窗** 权限（防 MIUI 冻结进程）
4. **不设锁屏密码**（否则熄屏语音不可用，详见手册第二十节）

---

## 已知限制

| 限制 | 说明 |
|---|---|
| 必须无锁屏密码 | 有 PIN/图案时无法自动划开锁屏 |
| 强依赖 DSH Go 的 UI 结构 | 它更新界面文案后，无障碍查找会失效 |
| MIUI 会冻结后台进程 | 靠 1×1 透明悬浮窗 + 前台服务 + 看门狗缓解 |
| 公网链路带宽低 | VPS 中转约 0.4 MB/s，局域网可达 22 MB/s |

---

## 文档

完整手册（约 35 KB，20 节）在 [`docs/DSH手势桥-手册.md`](docs/DSH手势桥-手册.md)，
包含每一步的实测日志、踩过的坑、排查表。

几个值得一看的章节：

- **第六节** 故障排查表
- **第七节** 这一路踩过的坑（都是实机验证的）
- **第十七节** Tasks.org 集成（第三方 API 的三条硬规则）
- **第二十节** 熄屏 / 锁屏下的语音链路

---

## 与 DSH Go 的关系

本项目**不修改 DSH Go 的任何代码**，完全通过公开接口协作：

- 启动它的 Activity
- 用无障碍读写它的输入框和按钮
- 读取它的界面文本来判断"是否在生成"

> DSH Go 的插件系统刻意不提供"发送消息"能力（`insert` 效果的说明是
> *"Never sends"*），所以只能走无障碍这条路。

---

## 许可

MIT。见 [LICENSE](LICENSE)。

本项目不包含 DSH Go 的任何代码，DSH Go 本身是 AGPL-3.0。
