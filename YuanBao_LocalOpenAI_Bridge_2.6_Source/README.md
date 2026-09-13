# YuanBao Local OpenAI Bridge 2.6

这是一个 LSPatch/Xposed 模块，用于在已授权、正在运行的 YuanBao Android 进程内部，把 YuanBao 自己的发送链路桥接为仅监听本机 `127.0.0.1:8318` 的 OpenAI-compatible API。

## 数据流

`POST /v1/chat/completions` → `hb.I6.s3(text,"",false)` → `MessageForSendPreprocessor` → `I7.a4.m/A` → `androidx.appcompat.widget.n1.e()` → `qr.d` → `pi.e` SSE callbacks → OpenAI SSE。

## API

- `GET http://127.0.0.1:8318/health`
- `GET http://127.0.0.1:8318/v1/models`
- `POST http://127.0.0.1:8318/v1/chat/completions`

请求示例：

```json
{"model":"yuanbao","messages":[{"role":"user","content":"你好"}],"stream":true}
```

## 重要限制

1. 服务只绑定 `127.0.0.1`，不是公网服务器。
2. 认证、登录态、Cookie、Uskey、设备标识和签名材料不会被导出、打印或自行生成；网络请求仍由 YuanBao 自己完成。
3. `hb.I6.s3()` 依赖当前处于可用状态的会话/会话 UI 实例。模块会在 `hb.a1.onResume()` 缓存活动实例，因此首次启动 YuanBao 后应进入会话页面。
4. `stream:true` 是主要模式。`stream:false` 使用同一 SSE 链路在内存中等待完成后返回普通 Chat Completion。
5. 当前解析器针对分析中确认的 `type=contents/content` 与 `content` JSON 事件；未知事件会忽略，不会把认证/控制数据当作文本转发。
6. 该版本不保证对未来 YuanBao 混淆符号或内部字段改名保持兼容，需要根据新版本日志重新确认 Hook 点。

## 真机验证

1. LSPatch/LSPosed 注入 YuanBao。
2. 启动 YuanBao 并进入正常聊天页面。
3. 检查日志 `yuanbao_bridge.log` 中是否出现 `[CTX] active=`。
4. `curl http://127.0.0.1:8318/health`
5. `curl http://127.0.0.1:8318/v1/models`
6. 用 `POST /v1/chat/completions` 测试 `stream:true`。
7. 日志应依次出现 `[SEND]`、`[MSG]`、`[REQ]`、`[SSE]`。

## 构建

原始工程缺少 `gradle/wrapper/gradle-wrapper.jar`，因此本压缩包保留源码和 Gradle 配置，但当前工作环境无法声称已经成功编译 APK。请在 AIDE/Android Studio/完整 Gradle Wrapper 环境中构建。
