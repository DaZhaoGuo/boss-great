---
name: android-debug-install
description: >-
  Incrementally builds and installs the boss-greet debug APK via adb.
  Use after any Android code/resource change in boss-greet, when the user asks
  to install/部署/装到手机, or when finishing a fix that needs device verification.
---

# boss-greet 增量编译安装

改完本工程 Android 代码或资源后，默认立刻增量编译并安装，不要只告诉用户自己装。

## 默认命令

在仓库根目录执行：

```bash
./scripts/install-debug.sh
```

需要先跑单元测试时：

```bash
./scripts/install-debug.sh --test
```

手机未连接时脚本会等待最多 120 秒；可加 `--wait 60` 调整。

## 规则

1. **优先脚本**：不要手写一长串 `gradle`/`adb`；调用 `scripts/install-debug.sh`。
2. **增量**：只跑 `:app:assembleDebug`（脚本默认），依赖 Gradle 增量编译；不要无量 `clean`。
3. **测试**：逻辑改动且有现成单测时加 `--test`；纯文案/布局可直接安装。
4. **环境**：`ANDROID_HOME` / `ANDROID_SDK_ROOT` 指向 `/Users/darrenguo/Library/Android/sdk`（脚本已设置）。
5. **安装后**：若改了无障碍能力或服务配置，提醒用户关闭再打开「打招呼助手」；一般代码改动安装即可。
6. **无设备**：`adb devices` 为空时继续等或告知连接 USB；不要假装已安装。

## 成功标准

脚本打印 `installed HH:MM:SS` 且 `adb install` 退出码为 0。
