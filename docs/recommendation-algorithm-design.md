# 歌曲推荐算法设计文档

## 1. 文档信息
- 文档名称：歌曲推荐算法设计文档
- 版本：v1.0
- 日期：2026-02-13
- 状态：已实现
- 依据文档：`docs/webdav-music-scan-development-design.md`

## 2. 背景与目标

### 2.1 背景
浏览页（Browse）当前使用随机关键字搜索伪造推荐内容，内容质量差且与用户偏好无关。需要建立真正的推荐系统，基于用户播放行为和曲库元数据生成个性化推荐货架。

### 2.2 目标
- 基于播放事件（播放开始、播放完成、跳过）采集用户行为信号。
- 构建 6 种推荐货架，覆盖"热门"、"新歌"、"新专辑"、"常听艺人"、"风格混搭"、"重新发现"场景。
- 前端浏览页消费推荐 API，按货架类型动态渲染。

### 2.3 非目标
- 不做协同过滤或机器学习模型（个人音乐库场景无需多用户画像）。
- 不做推荐结果缓存表（数据量小，实时 SQL 聚合足够快）。
- 不做推荐反馈闭环（不标记"不喜欢"）。

### 2.4 设计约束
- 个人/家庭音乐库规模：几百到几千首歌，百万级以下。
- 单用户场景，无用户 ID 维度（所有播放事件属于同一用户）。
- 技术栈：Java 8 + Spring Boot 2.7 + MyBatis + MySQL 5.7。
- MySQL 5.7 无窗口函数/CTE，SQL 需降级写法。

## 3. 整体架构

### 3.1 数据流

```
┌─────────────┐     POST /play-event     ┌──────────────┐     INSERT     ┌──────────────┐
│  前端 Player  │ ──────────────────────▶ │ PlayEvent    │ ────────────▶ │  play_event  │
│  Context     │   (fire-and-forget)     │ Controller   │              │    表         │
└─────────────┘                          └──────────────┘              └──────┬───────┘
                                                                             │
                                                                             ▼
┌─────────────┐   GET /recommendations   ┌──────────────┐    SQL 聚合    ┌──────────────┐
│  前端 Browse  │ ◀───────────────────── │ Recommendation│ ◀──────────── │ play_event   │
│  页面        │   List<ShelfResponse>   │ Service      │              │ + track 表   │
└─────────────┘                          └──────────────┘              └──────────────┘
```

### 3.2 模块划分

| 模块 | 职责 | 关键类 |
|------|------|--------|
| 事件采集（前端） | 在播放器中上报 PLAY_START / PLAY_COMPLETE / SKIP | `play-event-service.ts`、`PlayerContext.tsx` |
| 事件存储（后端） | 接收事件并入库 | `PlayEventController`、`PlayEventService`、`PlayEventMapper` |
| 推荐引擎（后端） | 6 个货架构建器 + 编排层 | `RecommendationService`、`PlayEventMapper`（聚合查询）、`TrackMapper`（推荐查询） |
| 推荐消费（前端） | 调用 API 并渲染货架 | `recommendation-service.ts`、`browse.tsx` |

## 4. 数据模型

### 4.1 play_event 表（V11 迁移）

```sql
CREATE TABLE play_event (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    track_id     BIGINT       NOT NULL,
    event_type   VARCHAR(20)  NOT NULL DEFAULT 'PLAY_START',
    duration_sec INT          NOT NULL DEFAULT 0,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_play_event_track_id (track_id),
    INDEX idx_play_event_created_at (created_at),
    INDEX idx_play_event_type (event_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

**字段说明：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | BIGINT PK | 自增主键 |
| `track_id` | BIGINT | 关联 track.id（不加外键，允许 track 被删除后事件保留） |
| `event_type` | VARCHAR(20) | `PLAY_START` / `PLAY_COMPLETE` / `SKIP` |
| `duration_sec` | INT | 事件发生时已播放秒数 |
| `created_at` | DATETIME | 事件时间戳 |

**事件类型权重：**

| 事件类型 | 权重 | 触发时机 |
|----------|------|----------|
| `PLAY_START` | +1 | 播放开始，获取到 playbackSession 后 |
| `PLAY_COMPLETE` | +3 | 播放进度 ≥ 歌曲时长 80%（每首歌仅上报一次） |
| `SKIP` | -1 | 切歌时已播放时长 < 30 秒 |

### 4.2 无缓存表设计决策

**决策：不建 `recommendation_shelf` 缓存表，直接实时 SQL 聚合。**

理由：
1. 数据量小：个人库 < 10,000 首歌，play_event 表日增几十到几百条。
2. 聚合查询快：带索引的 `GROUP BY` + `JOIN` 在此规模下 < 50ms。
3. 避免过度工程：缓存表需要 TTL 刷新机制、数据一致性维护，增加了不必要的复杂性。
4. 实时性更好：每次打开浏览页都能看到最新的推荐。

## 5. 推荐算法详解

### 5.1 货架总览

| 货架类型 | 中文标题 | 数据来源 | 响应类型 | 默认条数 |
|----------|----------|----------|----------|----------|
| `HOT_TRACKS` | 正在流行 | play_event 热度聚合 | tracks | 20 |
| `RECENT_ADDED` | 最新歌曲 | track.created_at | tracks | 20 |
| `RECENT_ALBUMS` | 最新专辑 | track GROUP BY album | albums | 20 |
| `FAVORITE_ARTISTS` | 常听艺人 | play_event 按 artist 聚合 | artists | 20 |
| `GENRE_MIX` | 风格混搭 | play_event TOP genre → 随机取歌 | tracks | 20 |
| `REDISCOVER` | 重新发现 | 排除近期播放的随机歌曲 | tracks | 20 |

### 5.2 HOT_TRACKS — 热度算法

**核心公式：**

```
heat(track) = SUM(event_weight) / LN(avg_hours_since_events + 2)
```

**SQL 实现：**

```sql
SELECT pe.track_id,
  SUM(CASE pe.event_type
    WHEN 'PLAY_COMPLETE' THEN 3
    WHEN 'PLAY_START'    THEN 1
    WHEN 'SKIP'          THEN -1
    ELSE 0 END
  ) / LN(AVG(TIMESTAMPDIFF(HOUR, pe.created_at, NOW())) + 2) AS heat
FROM play_event pe
WHERE pe.created_at >= DATE_SUB(NOW(), INTERVAL 30 DAY)
GROUP BY pe.track_id
HAVING heat > 0
```

**算法特性：**
- **时间衰减**：`LN(avg_hours + 2)` 确保越久远的播放事件贡献越小。`+2` 避免对数为 0。
- **行为加权**：完整播放（+3）远大于开始播放（+1），跳过（-1）是负信号。
- **HAVING heat > 0**：过滤掉被大量跳过的歌曲。
- **30 天窗口**：只看最近 30 天的播放行为。

**示例：**

假设歌曲 A 在过去 30 天内：
- 5 次 PLAY_COMPLETE（weight = 15）
- 2 次 PLAY_START 未完成（weight = 2）
- 1 次 SKIP（weight = -1）
- 平均事件距今 48 小时

```
heat = (15 + 2 - 1) / LN(48 + 2) = 16 / 3.91 ≈ 4.09
```

### 5.3 RECENT_ADDED — 最新歌曲

```sql
SELECT id, title, artist, album, source_path, duration_sec, has_lyric
FROM track
WHERE is_deleted = 0
ORDER BY created_at DESC
LIMIT 20
```

纯数据驱动，无需播放历史。适合冷启动（新用户还没播放过歌曲时仍能展示）。

### 5.4 RECENT_ALBUMS — 最新专辑

```sql
SELECT t.album, t.artist,
       COUNT(*) AS trackCount,
       MIN(t.id) AS coverTrackId,
       MAX(t.`year`) AS `year`
FROM track t
WHERE t.is_deleted = 0 AND t.album IS NOT NULL AND t.album <> ''
GROUP BY t.album, t.artist
ORDER BY MAX(t.created_at) DESC
LIMIT 20
```

**设计要点：**
- 按 `(album, artist)` 去重，同名专辑不同艺人分开展示。
- `coverTrackId` 取 `MIN(id)` 作为代表曲目，用于前端获取封面。
- 排序依据是专辑内最新曲目的入库时间。

### 5.5 FAVORITE_ARTISTS — 常听艺人

**两步查询：**

1. 从 play_event 聚合 TOP N 艺人：

```sql
SELECT t.artist
FROM play_event pe
INNER JOIN track t ON t.id = pe.track_id AND t.is_deleted = 0
WHERE pe.created_at >= DATE_SUB(NOW(), INTERVAL 30 DAY)
  AND t.artist IS NOT NULL AND t.artist <> ''
GROUP BY t.artist
ORDER BY SUM(CASE pe.event_type
  WHEN 'PLAY_COMPLETE' THEN 3
  WHEN 'PLAY_START' THEN 1
  WHEN 'SKIP' THEN -1
  ELSE 0 END) DESC
LIMIT 20
```

2. 对每个艺人查询 `trackCount` 和 `coverTrackId`：

```sql
SELECT COUNT(*) AS trackCount, MIN(id) AS coverTrackId
FROM track WHERE is_deleted = 0 AND artist = ?
```

### 5.6 GENRE_MIX — 风格混搭

**三步构建：**

1. 从 play_event 获取 TOP 3 流派（与 FAVORITE_ARTISTS 同样的加权逻辑）。
2. 从每个流派随机取 `20 / 3 ≈ 7` 首歌（`ORDER BY RAND() LIMIT 7`）。
3. 混合所有歌曲并 shuffle。

**设计要点：**
- 依赖播放历史，冷启动时此货架不展示。
- 随机性保证每次刷新看到不同的内容。
- 跨流派混合让用户发现跨界好歌。

### 5.7 REDISCOVER — 重新发现

**两步构建：**

1. 获取最近 60 天播放过的所有 track_id：

```sql
SELECT DISTINCT track_id FROM play_event
WHERE created_at >= DATE_SUB(NOW(), INTERVAL 60 DAY)
```

2. 从未播放歌曲中随机取 20 首：

```sql
SELECT ... FROM track
WHERE is_deleted = 0 AND id NOT IN (...)
ORDER BY RAND()
LIMIT 20
```

**设计要点：**
- 60 天窗口比 HOT_TRACKS 的 30 天更宽，确保确实是"被遗忘"的歌。
- 当播放历史为空时，等效于全库随机推荐。
- 帮助用户重新发现库中被忽略的音乐。

## 6. API 设计

### 6.1 播放事件上报

```
POST /api/v1/tracks/{id}/play-event
```

**请求体：**

```json
{
  "eventType": "PLAY_COMPLETE",
  "durationSec": 195
}
```

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `eventType` | String | 必填，`PLAY_START` / `PLAY_COMPLETE` / `SKIP` | 事件类型 |
| `durationSec` | Integer | 必填，≥ 0 | 事件时已播放秒数 |

**响应：** `204 No Content`

**前端上报策略：**

| 触发时机 | eventType | durationSec |
|----------|-----------|-------------|
| `playTrack()` 获取到 session 后 | `PLAY_START` | 0 |
| 播放进度 ≥ 歌曲时长 × 80% | `PLAY_COMPLETE` | 当前进度秒数 |
| 切歌且已播放 < 30 秒 | `SKIP` | 当前进度秒数 |

**防重复机制：**
- `PLAY_COMPLETE` 使用 `playCompleteReportedForRef` 记录已上报的 trackId，同一首歌仅上报一次。
- 切歌时重置该 ref。

**容错策略：**
- 所有上报均为 fire-and-forget（`.catch(() => undefined)`），不阻塞播放。
- 网络错误静默吞掉，不影响用户体验。

### 6.2 推荐货架查询

```
GET /api/v1/recommendations/shelves
```

**响应：**

```json
{
  "code": "0",
  "message": "OK",
  "data": [
    {
      "shelfType": "HOT_TRACKS",
      "title": "正在流行",
      "tracks": [
        {
          "id": 42,
          "title": "夜曲",
          "artist": "周杰伦",
          "album": "十一月的萧邦",
          "sourcePath": "/music/jay/nightcurve.flac",
          "durationSec": 225,
          "hasLyric": 1
        }
      ]
    },
    {
      "shelfType": "RECENT_ALBUMS",
      "title": "最新专辑",
      "albums": [
        {
          "album": "十一月的萧邦",
          "artist": "周杰伦",
          "trackCount": 12,
          "coverTrackId": 42,
          "year": 2005
        }
      ]
    },
    {
      "shelfType": "FAVORITE_ARTISTS",
      "title": "常听艺人",
      "artists": [
        {
          "artist": "周杰伦",
          "trackCount": 156,
          "coverTrackId": 42
        }
      ]
    }
  ]
}
```

**响应结构说明：**

| 字段 | 说明 |
|------|------|
| `shelfType` | 货架类型枚举 |
| `title` | 中文标题，前端直接展示 |
| `tracks` | 歌曲列表（HOT_TRACKS / RECENT_ADDED / GENRE_MIX / REDISCOVER） |
| `albums` | 专辑列表（仅 RECENT_ALBUMS） |
| `artists` | 艺人列表（仅 FAVORITE_ARTISTS） |

每个货架恰好有 `tracks`、`albums`、`artists` 中的一个非空（`@JsonInclude(NON_NULL)` 隐藏空字段）。

**空货架行为：**
- 没有足够数据的货架直接从列表中省略。
- 如果所有货架都为空（冷启动），返回空数组 `[]`。
- 前端展示引导文案"播放一些歌曲后这里会出现个性化推荐"。

## 7. 前端集成

### 7.1 play-event 上报集成点

```
PlayerContext.tsx
├── playTrack() .then() → reportPlayEvent(trackId, 'PLAY_START', 0)
├── onProgressUpdate() → if (progress >= duration * 0.8) → reportPlayEvent(..., 'PLAY_COMPLETE', progress)
└── control('next'/'previous') → if (progress < 30) → reportPlayEvent(..., 'SKIP', progress)
```

### 7.2 browse.tsx 消费逻辑

```
BrowseScreen
├── loadShelves() → fetchRecommendationShelves(client)
├── renderShelf(shelf) → switch by content type:
│   ├── shelf.albums   → renderAlbumShelf()  → <AlbumCard />
│   ├── shelf.artists  → renderArtistShelf() → <ArtistCard />
│   └── shelf.tracks   → renderTrackShelf()  → <SongCard />
└── empty state → "播放一些歌曲后这里会出现个性化推荐"
```

### 7.3 前端文件清单

| 文件 | 职责 |
|------|------|
| `src/contracts/play-event.ts` | PlayEventType / PlayEventPayload 类型定义 |
| `src/contracts/recommendation.ts` | ShelfResponse / ShelfTrackItem / ShelfAlbumItem / ShelfArtistItem 类型定义 |
| `src/services/player/play-event-service.ts` | `reportPlayEvent()` — fire-and-forget POST |
| `src/services/recommendation/recommendation-service.ts` | `fetchRecommendationShelves()` — GET 请求 |
| `app/(tabs)/browse.tsx` | 浏览页，消费推荐 API 并渲染 |

## 8. 后端实现

### 8.1 文件清单

| 层级 | 文件 | 职责 |
|------|------|------|
| DB Migration | `V11__play_event.sql` | play_event 表创建 |
| Domain | `PlayEventType.java` | 枚举：PLAY_START / PLAY_COMPLETE / SKIP |
| Domain | `ShelfType.java` | 枚举：6 种货架类型 |
| Entity | `PlayEventEntity.java` | play_event 表映射 |
| Mapper | `PlayEventMapper.java` | insert + 4 个聚合查询方法 |
| Mapper | `TrackMapper.java`（扩展） | 5 个推荐查询方法 |
| Request DTO | `PlayEventRequest.java` | 事件上报请求体 |
| Response DTO | `ShelfResponse.java` | 货架响应（含静态工厂方法） |
| Response DTO | `ShelfAlbumResponse.java` | 专辑货架条目 |
| Response DTO | `ShelfArtistResponse.java` | 艺人货架条目 |
| Service | `PlayEventService.java` | 事件入库 |
| Service | `RecommendationService.java` | 6 个货架构建器 + 编排 |
| Controller | `PlayEventController.java` | POST /tracks/{id}/play-event |
| Controller | `RecommendationController.java` | GET /recommendations/shelves |

### 8.2 容错设计

每个货架构建器独立包裹 `try-catch`：

```java
private ShelfResponse buildHotTracks() {
    try {
        List<TrackEntity> tracks = playEventMapper.selectHotTracks(HOT_DAYS, SHELF_LIMIT);
        if (tracks.isEmpty()) return null;
        return ShelfResponse.ofTracks("HOT_TRACKS", "正在流行", toTrackResponses(tracks));
    } catch (Exception e) {
        log.warn("Failed to build HOT_TRACKS shelf", e);
        return null;
    }
}
```

**效果：** 一个货架查询失败不会拖垮整个 API，其他货架照常返回。

### 8.3 可配置参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `HOT_DAYS` | 30 | 热度算法回溯天数 |
| `REDISCOVER_DAYS` | 60 | "重新发现"排除近期播放的天数 |
| `SHELF_LIMIT` | 20 | 每个货架的最大条目数 |
| `GENRE_MIX_TOP_N` | 3 | "风格混搭"取 TOP N 流派 |

当前硬编码在 `RecommendationService` 中，后续可迁移至 `application.yml` 配置。

## 9. 冷启动策略

| 场景 | 可用货架 | 用户体验 |
|------|----------|----------|
| 全新用户，无播放历史 | RECENT_ADDED, RECENT_ALBUMS, REDISCOVER（全库随机） | 仍有 3 个货架可展示 |
| 播放了几首歌 | 上述 + HOT_TRACKS | 逐步增加 |
| 播放了不同风格的歌 | 上述 + GENRE_MIX | 风格推荐解锁 |
| 持续播放一段时间 | 全部 6 个货架 | 完整推荐体验 |

**关键设计：** RECENT_ADDED、RECENT_ALBUMS、REDISCOVER 不依赖播放历史，确保新用户不会看到完全空白的浏览页。

## 10. 性能评估

### 10.1 数据规模假设

| 维度 | 规模 |
|------|------|
| 曲库大小 | 1,000 – 10,000 首 |
| play_event 日增 | 50 – 500 条 |
| play_event 30 天累计 | 1,500 – 15,000 条 |
| 同时在线用户 | 1（个人/家庭场景） |

### 10.2 查询性能估算

| 查询 | 预估耗时 | 依赖索引 |
|------|----------|----------|
| HOT_TRACKS 聚合 | < 20ms | idx_play_event_created_at |
| TOP_ARTISTS 聚合 | < 15ms | idx_play_event_created_at + track PK |
| TOP_GENRES 聚合 | < 15ms | idx_play_event_created_at + track PK |
| RECENT_ADDED | < 5ms | track created_at 索引 |
| RECENT_ALBUMS GROUP BY | < 10ms | track created_at 索引 |
| REDISCOVER (NOT IN + RAND) | < 30ms | idx_play_event_created_at + track PK |
| **总 API 响应** | **< 100ms** | — |

### 10.3 索引覆盖

play_event 表已有三个索引：
- `idx_play_event_track_id`：JOIN track 时使用
- `idx_play_event_created_at`：时间窗口过滤
- `idx_play_event_type`：事件类型过滤（可选，聚合查询中用处不大）

track 表已有索引：
- `PK (id)`：JOIN 使用
- `created_at`：RECENT_ADDED 排序
- `(album, artist)`：RECENT_ALBUMS GROUP BY

## 11. 未来扩展方向

| 方向 | 说明 | 优先级 |
|------|------|--------|
| 播放次数展示 | 在歌曲详情页展示播放次数 | P1 |
| 时段推荐 | 根据一天中的时段推荐不同风格（早晨轻柔、晚上动感） | P2 |
| 收藏加权 | 将收藏行为也纳入热度计算 | P2 |
| 参数配置化 | 将 HOT_DAYS/SHELF_LIMIT 等参数移到 application.yml | P3 |
| 缓存层 | 如果曲库增长到万级以上，考虑加 Redis 缓存货架结果 | P3 |
| 更多货架 | "相似歌曲"（基于元数据相似度）、"年代回忆"（按年代推荐） | P3 |

## 12. 变更日志

| 版本 | 日期 | 变更内容 |
|------|------|----------|
| v1.0 | 2026-02-13 | 初始版本：3 步实现方案（事件采集 → 推荐引擎 → 前端消费） |
