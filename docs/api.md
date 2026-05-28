# API

Base URL: `http://localhost:8080`

## POST /api/sessions

Request:

```json
{"nickname":"auditor"}
```

Response:

```json
{"sessionId":"...","token":"...","expiresAt":"..."}
```

## POST /api/plans

Header: `X-Session-Token: <token>`

Request:

```json
{"message":"今天下午带老婆孩子出去玩，孩子5岁，老婆在减肥，别离家太远"}
```

Response includes `planId`, `intent`, `options`, `trace`.

`trace` items include:

```json
{
  "tool": "AmapPoiSearchTool",
  "status": "ok",
  "durationMs": 120,
  "provider": "amap",
  "mode": "real",
  "sourceUrl": "https://restapi.amap.com/v3/place/text",
  "externalStatus": "10000"
}
```

正常环境中，高德或 MiMo 缺 key、失败、超时或校验不通过时，不会生成假地点。接口返回非 2xx JSON：

```json
{
  "error": "抱歉，暂时无法获取地点信息，请稍后重试",
  "planId": "...",
  "trace": [],
  "provider": "amap",
  "status": "ERROR"
}
```

Tavily 失败只记录 `mode: fallback`，不阻断方案生成。`test` profile 才允许 mock POI。

## GET /api/plans/{id}

Returns persisted plan session, selected option and execution results when available.

## POST /api/plans/{id}/confirm

Request:

```json
{"rank":1}
```

Response includes Mock orders and share message.

## POST /api/plans/{id}/feedback

Request:

```json
{"message":"不要太远，晚饭换成清淡一点"}
```

Response returns a regenerated plan.
