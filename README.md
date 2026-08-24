# DailyController

> DailyTask 的配套远程控制端（Android App），通过 MQTT 远程控制被控端的 DailyTask 设备。
> 兼容 Android 8.0 ~ 16.0（minSdk 26 / targetSdk 36）。

[![API](https://img.shields.io/badge/API-26%2B~36-green.svg)](https://developer.android.com)
[![Target SDK](https://img.shields.io/badge/Target%20SDK-36-blue.svg)](https://developer.android.com)
[![JDK](https://img.shields.io/badge/JDK-17-orange.svg)](https://www.oracle.com/java)
[![Build](https://github.com/yamleaf/DailyController/actions/workflows/build.yml/badge.svg)](https://github.com/yamleaf/DailyController/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/License-PolyForm%20NC%201.0.0-lightgrey.svg)](LICENSE)

---

## 目录

- [软件介绍](#软件介绍)
- [功能特性](#功能特性)
- [通信机制](#通信机制)
- [快速开始](#快速开始)
- [本地构建](#本地构建)
- [自动构建](#自动构建)
- [相关项目](#相关项目)
- [许可证](#许可证)

---

## 软件介绍

DailyController 是 DailyTask 的官方控制端应用。通过扫描被控端展示的绑定二维码，即可远程控制 DailyTask 设备：打卡任务启停、手动打卡、考勤记录导出、设置项下发、快照与告警查看等，无需直接接触被控端手机。

| 序号 | 说明 |
|:--:|:---|
| 1 | 扫码绑定被控端，一套 App 管理多台设备 |
| 2 | 基于 MQTT 通信，支持设备在线/离线状态实时同步 |
| 3 | 轻量运行：控制页打开时连接，关闭即断开；离线监控为可选前台服务 |
| 4 | 全本地数据存储（Room），无任何服务端收集用户数据 |

---

## 功能特性

### 设备管理

- **多设备卡片列表**：名称、ID、状态药丸（在线·绑定 / 在线·解绑 / 离线·解绑 / 等待配对 / 未知）
- **列表操作**：分组 Chip 过滤、关键词搜索、置顶、长按拖拽排序、左滑快捷面板（分组/置顶/重命名/删除）
- **在线探活**：逐设备临时短连接读取 retained 在线状态（3 秒超时判定离线），结果缓存 30 秒；
  在线时顺带校验会话有效性，验签失败自动置回解绑态

### 绑定与配对

- **三种添加方式**：扫码添加 / 剪贴板导入 / 系统分享导入配对 JSON
- **安全握手**：发布 `P` 配对请求 → 被控端回 `PA`（pairingToken 验签）→ 双端以 HKDF-SHA256
  派生会话密钥，仅保存在本机数据库（12 秒超时自动重试，最多 3 次）
- **解绑双向同步**：本机解绑下发 `UB` 并删除记录；被控端强制/主动解绑经签名状态信封识别，
  自动清理本地绑定并禁用控件

### 设备控制页（五个子页）

| 子页 | 内容 |
|:---|:---|
| **总览** | MQTT 连接开关、呼吸状态灯（绿/黄/红/灰）、2×3 快捷操作（手动打卡 / 考勤记录 / 执行任务 / 终止任务 / 开启循环 / 关闭循环）、打卡概览（已打卡/计划/错过/下次打卡）、运行概览（前台服务/任务调度/省电模式/伪息屏/WiFi/蓝牙/每日循环/下次重置/今日掉线统计等）、电量进度 + 走势曲线 + 耗尽预测、最近指令回执、告警历史 |
| **任务** | 打卡任务列表增删改（Material 时间选择器），一键下发被控端生效 |
| **日历** | 月历网格视图 + 近 14 天打卡记录 |
| **设置** | 被控端全部设置项分组远程下发：屏幕模式、伪息屏延时、省电模式、手势识别、节假日数据更新、自定义工作日、随机延迟、低电量告警阈值/段数/检测区间、消息渠道配置（凭据加密传输）等，并可查看被控端系统权限状态 |
| **设备** | 连接信息、硬件信息、`DT#` 指令一览、MQTT 流量配额统计、解绑设备 |

### 快照同步

- 下拉刷新（3 秒冷却）或手动触发全量快照拉取；首进控制页自动探活拉取
- 被控端状态变更经 `dt/{id}/push` 增量推送，按区块合并刷新，无需反复轮询

### 告警中心

- 接收被控端告警：低电量分段告警、智能预警（电量耗尽预测）、异常接入上报（`id_conflict`）
- **AQ 告警回放**：首进控制页自动拉取近期告警，补收控制端离线期间的漏报；清除黑名单防回放复活
- 按 `rid` 幂等入库去重，每设备保留最近 30 条，可清空、可查看详情

### 离线监控（可选）

- `OfflineMonitorService` 前台服务常驻订阅告警主题：`cleanSession=false` 保留 Broker 会话
  （约 2 小时内断线补收），设备离线/低电量弹系统通知
- 固定独立 clientId（`ctl-mon-{deviceId}`），与控制页连接互不干扰

### Serverless 管理

- 支持配置多个 EMQX Serverless 后台（REST API v5）
- 查看各后台当前在线客户端列表，一键强制下线异常实例

---

## 通信机制

```
┌─────────────────┐         MQTT           ┌─────────────────┐
│ DailyController │  ←───────────────────→  │    DailyTask     │
│  (控制端/客户端)  │   cmd / ack / status    │  (被控端/服务端)   │
│                 │   resp / push / alert   │                  │
└─────────────────┘                         └─────────────────┘
```

- **通信载体**：MQTT v3.1.1 Broker（Paho 1.2.5），地址由被控端绑定二维码提供；
  无端口默认补 `ssl://…:8883`（8883/8884/8886 走 TLS，其余走 tcp）
- **协议库**：[DailyProtocol](https://github.com/yamleaf/DailyProtocol)（git 子模块引入，
  当前 `PROTO_VER 2.0`），双端共享报文定义
- **安全模型**：
  - HMAC-SHA256 全量验签（会话密钥派生自 pairingToken，仅存两端本地）
  - `rid` 去重（最近 200 条）+ ±120 秒时钟窗防重放
  - 快照/推送/告警/消息渠道配置等敏感载荷先经 AES-256-GCM 信封加密再签名，公共 Broker 上只见密文
- **主题规划**：订阅 `dt/{id}/status`、`ack`、`resp`、`push`、`alert`、`pair/accept`；
  发布 `dt/{id}/cmd`（查询/设置/任务/动作/告警回放）、`dt/{id}/pair`（配对/解绑）
- **客户端隔离**：控制页 `ctl-{id}`、列表探活 `ctl-probe-{id}`、离线监控 `ctl-mon-{id}`
  等使用不同 clientId 连接，避免会话互踢
- **心跳**：依赖 Paho keepAlive 自动 PINGREQ（控制页 240 秒、监控服务 60 秒），无自定义心跳报文

---

## 快速开始

1. 在**被控端** DailyTask 中完成 MQTT 配置并生成绑定二维码
2. 打开 DailyController，点击「添加设备」扫描二维码
3. 设备出现在列表后点击进入，即可远程控制

> 权限说明：仅需 `INTERNET`（MQTT 通信）、`CAMERA`(扫码绑定) 与
> `POST_NOTIFICATIONS`（离线/低电量通知，Android 13+ 弹窗授权）。
> 离线监控为前台服务（specialUse 类型），可在设置中按需开关。

---

## 本地构建

```bash
# 需要 JDK 17 与 Android SDK 36
git clone --recurse-submodules https://github.com/yamleaf/DailyController.git
cd DailyController
./gradlew assembleDebug --no-daemon
# APK 输出：app/build/outputs/apk/debug/
```

- 技术栈：AGP 8.13.2 / Kotlin 2.3.21 / Room 2.8.2 / Material Design 3，Gradle Wrapper 自带（8.13）
- `protocol/` 为 git 子模块（协议库），克隆时需 `--recurse-submodules`
- APK 产物命名为 `dailyController_<构建来源>-<git短哈希>_<构建类型>.apk`
  （如 `dailyController_ci-abcdef1_debug.apk`），便于溯源包的出处与对应提交

---

## 自动构建

仓库已配置 GitHub Actions（`.github/workflows/build.yml`），在以下时机自动编译并上传 APK 产物：

- 推送 / PR 到 `master` 分支
- 手动触发（Actions 页面 → Run workflow）

构建产物可在 Actions 运行详情页的 **Artifacts** 中下载：

| Artifact 名称 | 内容 |
|:---|:---|
| `dailyController_debug` | 可调试安装包（未签名） |
| `dailyController_release` | 正式安装包（已签名） |

手动触发时会额外创建 GitHub 预发布（Release），附 CHANGELOG 摘要与版本信息。

---

## 相关项目

| 项目 | 说明 | 仓库 |
|:---|:---|:---|
| **DailyTask** | 无人值守打卡工具（被控端） | [yamleaf/DailyTask](https://github.com/yamleaf/DailyTask) |
| **DailyProtocol** | 双端共享 MQTT 协议库 | [yamleaf/DailyProtocol](https://github.com/yamleaf/DailyProtocol) |

---

## 许可证

本项目以 **PolyForm Noncommercial License 1.0.0** 发布，仅供非商业用途的学习与研究，禁止任何商业使用、倒卖或二次售卖。软件按「现状」提供，作者不对使用过程中产生的任何后果承担责任。
