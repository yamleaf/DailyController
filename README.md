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

DailyController 是 DailyTask 的官方控制端应用。通过扫描被控端展示的绑定二维码，即可远程控制 DailyTask 设备执行配置操作（如省电模式、强制伪息屏、伪息屏延时等），无需直接接触被控端手机。

| 序号 | 说明 |
|:--:|:---|
| 1 | 扫码绑定被控端，一套 App 管理多台设备 |
| 2 | 基于 MQTT 通信，支持设备在线/离线状态实时同步 |
| 3 | 轻量运行：控制端打开时连接，关闭即断开，不常驻后台 |
| 4 | 全本地数据存储（Room），无任何服务端收集用户数据 |

## 功能特性

- **扫码绑定**：扫描被控端生成的二维码，自动保存 MQTT 连接配置，一键添加设备
- **设备管理**：设备卡片列表展示名称与 ID，支持多设备管理与随时解绑
- **状态同步**：实时订阅设备在线/离线/解绑状态，连接异常可见
- **远程控制**：
  - 省电模式开关
  - 强制伪息屏开关
  - 伪息屏延时设置（10 ~ 600 秒）
- **Material Design 3**：现代化界面，适配深色模式

## 通信机制

```
┌──────────────┐         MQTT          ┌──────────────┐
│ DailyController│  ←──────────────────→  │   DailyTask   │
│  (控制端/客户端) │  cmd / ack / status   │  (被控端/服务端) │
└──────────────┘                       └──────────────┘
```

- 通信载体：MQTT Broker（由被控端绑定二维码提供连接信息）
- 协议：自定义 JSON 报文，HMAC-SHA256 签名验签，含 `rid` 防重放与时间戳校验
- 主题：`dt/{deviceId}/cmd`（指令）、`dt/{deviceId}/ack`（回执）、`dt/{deviceId}/status`（在线状态）
- 被控端详情见 [DailyTask](https://github.com/yamleaf/DailyTask)

## 快速开始

1. 在**被控端** DailyTask 中完成 MQTT 配置并生成绑定二维码
2. 打开 DailyController，点击「添加设备」扫描二维码
3. 设备出现在列表后点击进入，即可远程控制

> 权限说明：仅需 `INTERNET`（MQTT 通信）与 `CAMERA`（扫码），无任何敏感权限。

## 本地构建

```bash
# 需要 JDK 17 与 Android SDK 36
git clone https://github.com/yamleaf/DailyController.git
cd DailyController
./gradlew assembleDebug --no-daemon
# APK 输出：app/build/outputs/apk/debug/
```

## 自动构建

仓库已配置 GitHub Actions（`.github/workflows/build.yml`），在以下时机自动编译并上传 APK 产物：

- 推送 / PR 到 `master` 分支
- 手动触发（Actions 页面 → Run workflow）

构建产物可在 Actions 运行详情页的 **Artifacts** 中下载：

| Artifact 名称 | 内容 |
|:---|:---|
| `dailyController_debug` | 可调试安装包（未签名） |
| `dailyController_release` | 正式安装包（已签名） |

## 相关项目

| 项目 | 说明 | 仓库 |
|:---|:---|:---|
| **DailyTask** | 无人值守打卡工具（被控端） | [yamleaf/DailyTask](https://github.com/yamleaf/DailyTask) |
| **DailyController** | 远程控制端（本仓库） | [yamleaf/DailyController](https://github.com/yamleaf/DailyController) |

## 许可证

本项目以 **PolyForm Noncommercial License 1.0.0** 发布，仅供非商业用途的学习与研究，禁止任何商业使用、倒卖或二次售卖。软件按「现状」提供，作者不对使用过程中产生的任何后果承担责任。
