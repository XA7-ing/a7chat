# 开发笔记

## 架构

WebView 加载 `assets/index.html`，`MainActivity` 里起一个 `ServerSocket` 监听随机端口，
页面所有 `/api/*` 请求都打到本机，再由原生层带 `Authorization: Bearer` 转发给面板。
这样 WebView 不用处理跨域，也不用把 token 暴露给页面。

## 踩过的坑

- **流式中转**：面板把所有内容（包括 reasoning 和工具调用 JSON）都用 `type: "plain"` 逐字下发，
  没法靠 type 过滤。做法是累积 plain 文本，等 `complete` 事件到达后用它的正文覆盖。
- **落库延迟**：文字消息在 `complete` 之后约 1.7 秒才写进数据库，
  所以收尾后要延迟重拉历史（1.2s / 3.5s 两次），否则看不到刚落的消息。
- **附件鉴权**：`/api/chat/get_attachment` 需要 Authorization 头，图片必须走本机转发，不能直接给 `<img src>`。
- **上传**：`/api/v1/file` 是 multipart，字段名 `file`，返回 `attachment_id`。
- **发送消息**：`message` 可以传数组 `[{type:'plain',text},{type:'image',attachment_id,filename}]`；
  4.0 系列只收字符串，图片得单独放进 `image_url`。
- **老版本历史**：`content.message` 是字符串而不是数组，渲染前要包一层。

## 已知限制

- 没有通知栏提醒（后台容易被系统清理，评估后没做）
- 3.x 面板没有 webchat 这套接口，支持不了
- 老版本接口按 AstrBot `v4.0.0` 源码对齐，没在真实旧面板上回归过
