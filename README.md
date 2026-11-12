# A7chat

一个连接 [AstrBot](https://github.com/AstrBotDevs/AstrBot) 面板的 Android 客户端。

界面用 WebView 加载本地页面，网络请求由一个跑在 `127.0.0.1` 的迷你 HTTP 服务转发到面板，
不依赖任何第三方库（无 Retrofit / OkHttp / AndroidX），APK 只有 120 KB 左右。

## 功能

- 面板账号登录，登录态保存在本地
- 会话列表：新建、切换、重命名、删除
- 流式聊天，逐字显示
- 发图片、发文件、表情包管理
- 自定义「她的头像」和「我的头像」
- 主题跟随绿色系，图标为 A7

## 接口兼容

不同版本的 AstrBot 面板，聊天接口路径和数据结构都不一样。A7chat 登录后自动探测，分三档：

| 档位 | 面板版本 | 接口 |
| --- | --- | --- |
| 新版 | 4.2x 之后 | `/api/v1/chat/sessions`、`/api/v1/chat` |
| 兼容 | 4.1x 过渡期 | `/api/chat/sessions`、`/api/chat/send` |
| 老版本 | 4.0 ~ 4.1 | `/api/chat/conversations`、`/api/chat/get_conversation` |

字段差异在本地统一：`conversation_id → session_id`、`title → display_name`、
老版 `message` 字符串 → 分段数组、`image_url` → 附件字段。
登录本身也降级：`/api/v1/auth/login` 不通就走 `/api/auth/login`。

设置页底部会显示当前连的是哪一档。

## 构建

需要 JDK 17 和 Android SDK 34。

```bash
cp gradle.properties.example gradle.properties   # 填自己的签名信息
./gradlew assembleRelease
```

签名信息不放在仓库里，从 `gradle.properties` 或同名环境变量读：
`XGC_STORE_FILE`、`XGC_STORE_PASSWORD`、`XGC_KEY_ALIAS`、`XGC_KEY_PASSWORD`。
没配签名也能构建，只是产物未签名。

## 目录

```
app/src/main/assets/index.html     界面与交互
app/src/main/java/.../MainActivity 迷你 HTTP 服务 + 面板接口适配
app/src/main/res/mipmap-*/         图标
docs/NOTES.md                      开发笔记与踩坑
```

## 说明

非官方项目，和 AstrBot 开发团队没有关系。
面板密码没有存在云端，只在本机 SharedPreferences 里。
