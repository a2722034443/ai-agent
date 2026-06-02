# API 文档

Base URL: `http://localhost:8080`

除 `POST /api/sessions` 外，业务接口都需要请求头：

```http
X-Session-Token: <token>
Content-Type: application/json
```

## 状态说明

- `NEEDS_CLARIFICATION`: 信息不完整，需要前端展示澄清表单。
- `READY`: 已生成方案，可展示方案卡和地图。
- `COMPLETED`: 用户已确认某个方案，返回执行结果。
- `BLOCKED`: 关键 provider 或方案校验阻断。
- `NOT_READY`: 对未 READY 的计划执行确认等非法操作。
- `NOT_FOUND`: 计划不存在。

## POST /api/sessions

创建会话。

Request:

```json
{
  "nickname": "dev"
}
```

Response:

```json
{
  "sessionId": "uuid",
  "token": "sess_xxx",
  "expiresAt": "2026-06-01T00:00:00Z"
}
```

## POST /api/plans

创建规划或提交澄清答案。

### 初始请求

```json
{
  "message": "今天晚上7点在上海静安寺附近，4个朋友，预算800元，想先玩再吃饭",
  "planCount": 3,
  "stopCountPreference": "标准"
}
```

如果缺少字段，返回：

```json
{
  "planId": "uuid",
  "status": "NEEDS_CLARIFICATION",
  "intent": {},
  "options": [],
  "clarification": {
    "message": "还需要补齐几个关键信息，补齐后我再查询真实地点并生成方案。",
    "fields": [
      {
        "key": "duration",
        "label": "游玩时长",
        "question": "已识别开始时间 19:00，还需要知道大概玩多久，或者最晚几点结束。",
        "type": "text",
        "expectedAnswerHint": "例如：3小时左右、晚饭后结束、21:30前结束"
      }
    ]
  },
  "warnings": [
    "信息补齐前不会查询真实地点，也不会生成方案。"
  ],
  "trace": []
}
```

### 澄清请求

必须带上一轮 `previousPlanId`，否则后端无法合并上下文。

```json
{
  "message": "3小时左右",
  "planCount": 3,
  "stopCountPreference": "标准",
  "previousPlanId": "上一轮 planId",
  "clarificationAnswers": {
    "duration": "3小时左右"
  }
}
```

成功返回：

```json
{
  "planId": "uuid",
  "status": "READY",
  "intent": {
    "location": {
      "city": "上海",
      "district": "上海静安寺附近",
      "lng": 121.0,
      "lat": 31.0
    },
    "time_window": {
      "start": "19:00",
      "durationMinutes": 180
    }
  },
  "options": [
    {
      "rank": 1,
      "name": "稳妥轻松方案",
      "tagline": "距离更近，节奏更稳",
      "timeline": [
        {
          "time": "19:00",
          "name": "POI 名称",
          "type": "活动",
          "address": "地址",
          "lng": 121.0,
          "lat": 31.0,
          "rating": 4.7
        }
      ],
      "budgetEstimate": 800,
      "totalMinutes": 180,
      "route": {
        "distanceKm": 4.2,
        "travelMinutes": 22,
        "segmentMinutes": [11, 10],
        "routeModes": ["walking", "driving"]
      },
      "fitReasons": []
    }
  ],
  "trace": []
}
```

## GET /api/plans/{id}

查询计划详情。用于刷新页面或调试。

Response 与 `POST /api/plans` 的响应结构一致。

## POST /api/plans/{id}/confirm

确认某个方案，生成执行 mock 结果。

Request:

```json
{
  "rank": 1
}
```

Response:

```json
{
  "planId": "uuid",
  "status": "COMPLETED",
  "execution": {
    "orders": [],
    "shareMessage": "搞定啦，下午按方案出发..."
  }
}
```

如果计划还不是 READY：

```json
{
  "status": "NOT_READY",
  "error": "当前方案还不能确认，请先补齐信息并生成方案。"
}
```

## POST /api/plans/{id}/feedback

对已生成或澄清中的计划提交修改意见。

Request:

```json
{
  "message": "晚饭换成清淡一点，路线再近一点"
}
```

Response:

- READY 计划：返回重新生成或调整后的方案。
- NEEDS_CLARIFICATION 计划：继续返回澄清字段。

## POST /api/speech/transcribe

语音转文字接口。用于前端语音输入按钮上传录音文件，后端调用 ASR 引擎后返回识别文本。

Headers:

```http
X-Session-Token: <token>
```

Request:

```http
Content-Type: multipart/form-data
file: voice.wav
```

前端当前会把浏览器录音转成 `16kHz mono wav` 后上传。不要在浏览器端暴露阿里云 AccessKey。

Response:

```json
{
  "text": "今天晚上七点在上海静安寺附近，四个朋友，预算八百，想先玩再吃饭",
  "language": "zh",
  "durationMs": 1200,
  "engine": "aliyun",
  "traceId": "speech_xxx"
}
```

失败时返回：

```json
{
  "status": "TRANSCRIBE_FAILED",
  "error": "语音识别失败，请重试或直接输入文字"
}
```

常见错误：

- `INVALID_AUDIO`: 文件为空、过大或格式不支持。
- `TRANSCRIBE_FAILED`: ASR provider 调用失败或未识别到有效文本。

## WebSocket /api/speech/transcribe/stream

语音转文字实时流式接口。前端连接 WebSocket 后，将浏览器麦克风音频转换为 `16kHz mono pcm`，按小块发送二进制消息；后端把音频流转发给 ASR provider，并把识别片段实时推回前端。

Client messages:

```text
BinaryMessage: 16kHz mono pcm audio chunk
TextMessage: {"type":"end"}
```

Server message:

```json
{
  "type": "chunk",
  "text": "今天晚上七点在上海",
  "language": "zh",
  "engine": "stream",
  "traceId": "speech_xxx",
  "sequence": 1,
  "timestamp": 1780000000000,
  "finalChunk": false,
  "fallback": false
}
```

`sequence` 用于前端按顺序拼接；`timestamp` 用于实时性标识；`finalChunk=true` 表示当前句子或本轮识别结束；`fallback=true` 表示实时链路不可用时返回降级提示。
## POST /api/collab/shares

创建协同分享。

Request:

```json
{
  "planId": "uuid",
  "selectedRank": 1
}
```

Response:

```json
{
  "shareId": "uuid",
  "planId": "uuid",
  "selectedRank": 1,
  "shareUrl": "http://localhost:5173/share/uuid"
}
```

## POST /api/collab/shares/{shareId}/votes

提交投票。

```json
{
  "rank": 1,
  "voter": "同行人"
}
```

## POST /api/collab/shares/{shareId}/comments

提交评论。

```json
{
  "author": "同行人",
  "text": "能不能把吃饭时间调后半小时？"
}
```

## GET /api/memory

返回全员记忆 mock 标签。

```json
{
  "tags": ["老婆减肥", "孩子要亲子设施", "朋友不吃辣"]
}
```

## GET /api/guard/status

返回出行守护 mock 状态。

```json
{
  "steps": [
    {
      "name": "天气正常，适合出行",
      "status": "done"
    }
  ]
}
```

## trace 字段

每次规划会返回工具调用 trace，调试优先看这里。

```json
{
  "tool": "AmapPoiSearchTool",
  "status": "ok",
  "durationMs": 183,
  "provider": "amap",
  "mode": "real",
  "sourceUrl": "https://restapi.amap.com/v3/place/around",
  "externalStatus": "10000",
  "input": {},
  "output": {}
}
```

常见工具：

- `IntentParserAgent`
- `ClarificationAgent`
- `AmapPoiSearchTool`
- `AmapRouteEstimateTool`
- `AmapWeatherTool`
- `WebSearchTool`
- `PlanValidationService`
- `ExceptionRecoveryTool`
