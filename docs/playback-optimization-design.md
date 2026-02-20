# 播放链路性能优化设计文档

## 1. 文档信息
- 文档名称：播放链路性能优化设计文档
- 版本：v1.1
- 日期：2026-02-14
- 状态：已实现
- 依据文档：`docs/recommendation-algorithm-design.md`

## 2. 背景与目标

### 2.1 背景

用户反馈三个播放体验问题：
1. **点击播放感觉卡顿**：点击播放后要等网络拿到签名 URL 才有 UI 反馈。
2. **播放/暂停按钮状态反转**：按下暂停后按钮短暂正确 → 被后端响应覆盖回"播放中" → 按钮状态与实际相反。
3. **进度条拖动卡顿**：拖动时与音频 500ms 进度回报互相打架，且无节流。

### 2.2 根因分析

经完整播放链路排查，定位 6 个技术问题 + 3 个高延迟网络下的状态覆盖 bug：

| 编号 | 优先级 | 问题 | 根因 | 影响 |
|------|--------|------|------|------|
| P0-A | 🔴 紧急 | pause/resume 状态反转 | 乐观更新后发网络请求，后端响应/错误回滚覆盖本地状态 | 按钮状态与实际播放状态相反 |
| P0-B | 🔴 紧急 | 全树 500ms 重渲染 | `onProgressUpdate` 每 500ms 调用 `setPlaybackSession({...prev, progressSec})` 触发 Context 全消费者 re-render | 所有使用 `usePlayer()` 的组件每 500ms 重新渲染 |
| P1-C | 🟡 高 | StyleSheet 每帧重建 | `StyleSheet.create` 写在函数组件体内（render 阶段），NowPlayingScreen 60+ 样式对象每帧重建 | 不必要的 GC 压力和样式计算 |
| P1-D | 🟡 高 | 进度条拖动打架 | PanResponder 每帧直接调 `seekTo` 无节流，同时音频 500ms 进度回报覆盖拖动位置 | 拖动时进度条来回跳动 |
| P1-E | 🟡 高 | 播放启动无反馈 | `playTrack()` 要等 `requestTrackPlaybackSession` 网络响应后才创建 session | 用户点击播放后 200-500ms 无任何 UI 变化 |
| P2-F | 🟠 中 | useEffect 冗余调用 | `useAudioPlayer` 的 state useEffect 依赖 `session?.state` 引用，即使值不变也触发 `pauseAsync()/playAsync()` | 冗余的 native bridge 调用 |
| BUG-1 | 🔴 紧急 | buffering 回调覆盖暂停 | `onBuffering(false)` 无条件设置 `state: 'playing'` | 高延迟网络下用户暂停被 buffering 覆盖 |
| BUG-2 | 🔴 紧急 | 后端 merge 覆盖暂停 | `mergeNowPlayingStatus()` 无条件使用 `status.state` | 初始化/队列面板 fetch 覆盖本地暂停 |

### 2.3 目标

- 消除 pause/resume 状态反转问题（包括高延迟网络场景）
- 将 500ms 进度 tick 的 re-render 范围从全组件树缩小到仅 MiniPlayer + NowPlayingScreen
- 进度条拖动流畅，拖动期间无抖动
- 点击播放立即显示 buffering 态
- 兼容 Android 和 iOS

### 2.4 非目标

- 不重构 PlayerContext 的整体架构（改动最小化）
- 不引入新依赖（不用 zustand/jotai 替代 Context）
- 不修改后端 API

### 2.5 设计约束

- 技术栈：React Native 0.81 + Expo 54 + expo-av + TypeScript 5.9
- 状态管理：React Context（`PlayerContext`）
- 音频引擎：expo-av `Audio.Sound` 实例
- 跨平台：所有方案必须同时兼容 iOS 和 Android

## 3. 整体架构

### 3.1 优化前数据流

```
expo-av onPlaybackStatusUpdate (每 500ms)
  │
  ▼
onProgressUpdate(progressSec)
  │
  ├── setPlaybackSession({...prev, progressSec})  ← 触发 Context re-render
  │     │
  │     ▼
  │   PlayerContext.Provider value 变化
  │     │
  │     ├── MiniPlayer re-render          ← 需要 progressSec ✓
  │     ├── NowPlayingScreen re-render    ← 需要 progressSec ✓
  │     ├── BrowseScreen re-render        ← 不需要 ✗
  │     ├── LibraryScreen re-render       ← 不需要 ✗
  │     └── 所有 usePlayer() 消费者...    ← 不需要 ✗
  │
  └── control('pause')
        │
        ├── setPlaybackSession({state:'paused'})     ← 乐观更新 ✓
        ├── controlAudio('pause')                     ← 本地音频暂停 ✓
        └── requestPlaybackControl(...)               ← 网络请求
              │
              ├── .then(status) → mergeNowPlayingStatus → setPlaybackSession  ← 覆盖回 playing ✗
              └── .catch() → setPlaybackSession(previousSession)               ← 回滚到 playing ✗
```

### 3.2 优化后数据流

```
expo-av onPlaybackStatusUpdate (每 500ms)
  │
  ▼
onProgressUpdate(progressSec)
  │
  ├── progressSecRef.current = progressSec    ← 只更新 ref，不触发 re-render
  │
  └── notifyProgressListeners(progressSec)    ← 通知订阅者
        │
        ├── MiniPlayer (via usePlaybackProgress)  ← 局部 re-render ✓
        ├── NowPlayingScreen (via usePlaybackProgress) ← 局部 re-render ✓
        └── persistPlaybackSnapshot()              ← 写快照（有节流）

        BrowseScreen, LibraryScreen 等 ← 不受影响 ✓


control('pause')
  │
  ├── setPlaybackSession({state:'paused', lastCommand:'pause'})  ← 本地状态更新
  ├── controlAudio('pause')                  ← 本地音频暂停
  └── return;                                ← 直接返回，不走网络 ✓

  后续 onBuffering / mergeNowPlayingStatus
  │
  └── 检查 lastCommand === 'pause' → 保留 paused 状态 ✓
```

### 3.3 模块关系

| 模块 | 优化项 | 关键变更 |
|------|--------|----------|
| `PlayerContext.tsx` | P0-A, P0-B, P1-E, BUG-1 | pause/resume 本地化、进度订阅系统、buffering 占位、onBuffering 保护 |
| `useAudioPlayer.ts` | P2-F | state 值比较 |
| `NowPlayingScreen.tsx` | P0-B, P1-C, P1-D | usePlaybackProgress、useMemo StyleSheet、拖动防抖 |
| `MiniPlayer.tsx` | P0-B, P1-C | usePlaybackProgress、useMemo StyleSheet |
| `features/player/index.ts` | BUG-2 | mergeNowPlayingStatus 状态保护 |

## 4. 方案详解

### 4.1 P0-A: pause/resume 纯本地化

**问题链路：**

```
用户点击暂停
  → applyOptimisticPlaybackControl → state = 'paused' ✓
  → controlAudio('pause') → 音频暂停 ✓
  → requestPlaybackControl(POST /playback/control)
      → .then() → mergeNowPlayingStatus() → state = 'playing' ✗ (后端认为还在播放)
      → .catch() → setPlaybackSession(previousSession) → state = 'playing' ✗ (回滚)
```

**修复方案：**

在 `control()` 函数中，对 pause/resume 命令提前返回，只做本地状态更新 + 音频控制，不发网络请求：

```typescript
// PlayerContext.tsx control() 函数
if (command === 'pause' || command === 'resume') {
  const nextState = command === 'pause' ? 'paused' : 'playing';
  setPlaybackSession((prev) =>
    prev ? { ...prev, state: nextState, lastCommand: command } : prev
  );
  controlAudio(command).catch(() => undefined);
  return; // 不走网络，不会被覆盖或回滚
}
```

**设计决策：**
- pause/resume 是纯本地操作（expo-av 在设备上暂停/恢复），不需要后端确认。
- next/previous 仍走 optimistic + 网络确认（需要后端返回下一首歌的信息）。
- `lastCommand: command` 标记用于后续 buffering/merge 保护（见 4.7、4.8）。

### 4.2 P0-B: progressSec 从 session 分离

**问题本质：**

`playbackSession` 是 Context value 的一部分。每次 `setPlaybackSession({...prev, progressSec})` 都会导致 `useMemo` 生成新的 value 对象，触发所有 `usePlayer()` 消费者 re-render —— 即使绝大多数组件根本不关心 progressSec。

**修复方案：Ref + 订阅模式**

```
┌─ PlayerProvider ──────────────────────────────────────┐
│                                                        │
│  progressSecRef = useRef(0)                            │
│  progressListenersRef = useRef(new Set<Listener>())    │
│                                                        │
│  notifyProgressListeners(sec):                         │
│    progressSecRef.current = sec                        │
│    for listener of progressListenersRef → listener(sec)│
│                                                        │
│  subscribeProgress(listener) → unsubscribe fn          │
│  getProgressSec() → progressSecRef.current             │
│                                                        │
│  Context value 暴露 subscribeProgress + getProgressSec │
│  (两个 useCallback，引用稳定，不触发 re-render)        │
│                                                        │
└────────────────────────────────────────────────────────┘

┌─ usePlaybackProgress() hook ──────────────────────────┐
│                                                        │
│  const { subscribeProgress, getProgressSec } = usePlayer()  │
│  const [progress, setProgress] = useState(getProgressSec)   │
│                                                        │
│  useEffect(() => {                                     │
│    setProgress(getProgressSec())                       │
│    return subscribeProgress(setProgress)               │
│  }, [...])                                             │
│                                                        │
│  return progress  // 只有调用此 hook 的组件 re-render  │
└────────────────────────────────────────────────────────┘
```

**快照持久化适配：**

由于 `playbackSession` 不再因 progressSec 变化而更新，snapshot 写入改为订阅 progress 变化触发。`persistPlaybackSnapshot` 内部已有 `PLAYBACK_SNAPSHOT_WRITE_THROTTLE_MS = 2000` 节流和指纹去重，不会频繁写入。

### 4.3 P1-C: StyleSheet.create → useMemo

将 `StyleSheet.create` 从函数组件体（每次 render 执行）移到 `useMemo` 中，仅在主题变更时重建。动态样式（如 progressFill 的 width）提取为内联样式。

同样应用于 MiniPlayer.tsx 和 NowPlayingScreen.tsx。

### 4.4 P1-D: 进度条拖动优化

**问题链路：**

```
用户拖动进度条
  → onPanResponderMove (每帧触发，~16ms)
    → seekTo(sec)           ← 每帧发一次 native bridge 调用
    → expo-av 回报进度      ← 500ms 间隔回报旧位置
    → setPlaybackSession    ← 进度跳回旧位置
    → 进度条来回抖动
```

**修复方案：**

1. **本地拖动状态**：拖动期间用 `dragProgressSec` 状态控制显示，不依赖音频回报
2. **80ms 节流**：`throttledSeek` 限制 `seekTo` 调用频率
3. **松手最终定位**：`onPanResponderRelease` 执行最终 `seekTo`，然后清除 `dragProgressSec`

### 4.5 P1-E: playTrack 立即 buffering

在发网络请求前，立即创建一个 `state: 'buffering'` 的占位 session，让用户立即看到 UI 响应。网络返回签名 URL 后用正式 session 替换。

### 4.6 P2-F: useEffect 值比较

新增 `lastAppliedStateRef` 做值比较，避免 `session.state` 引用变化但值不变时重复调用 `pauseAsync()/playAsync()`。新轨道加载时重置。

### 4.7 BUG-1: onBuffering 回调覆盖暂停状态

**问题（高延迟网络下触发）：**

```
用户按暂停 → state = 'paused' (P0-A) ✓
  → 网络慢，音频还在 buffering
  → isBuffering = true → state 维持 'paused'（但原代码会进入 'buffering'）
  → isBuffering = false → 原代码无条件 state = 'playing' ✗
  → 用户的暂停被覆盖
```

**修复方案：**

```typescript
onBuffering: (isBuffering: boolean) => {
  setPlaybackSession((prev) => {
    if (!prev) return prev;
    // FIX: 用户已暂停时不进入 buffering
    if (isBuffering && prev.state === 'playing' && prev.lastCommand !== 'pause') {
      return { ...prev, state: 'buffering' };
    }
    if (!isBuffering && prev.state === 'buffering') {
      // FIX: buffering 结束时检查用户是否在此期间暂停过
      const shouldResume = prev.lastCommand !== 'pause';
      return { ...prev, state: shouldResume ? 'playing' : 'paused' };
    }
    return prev;
  });
},
```

**关键设计**：依赖 P0-A 设置的 `lastCommand` 标记判断用户意图。

### 4.8 BUG-2: mergeNowPlayingStatus 覆盖暂停状态

**问题（高延迟网络下触发）：**

三条路径调用 `mergeNowPlayingStatus` 可能覆盖本地暂停状态：
1. 初始化挂载时 `requestNowPlayingStatus`
2. 打开队列面板时 `requestNowPlayingStatus`
3. next/previous 命令的 `.then()` 回调

原实现中 `mergeNowPlayingStatus` 无条件使用 `status.state`（后端状态），如果后端还没收到暂停命令（P0-A 不发网络），就会返回 `state: 'playing'` 覆盖本地 `'paused'`。

**修复方案：**

```typescript
// features/player/index.ts mergeNowPlayingStatus()
const sameTrack = previousSession?.track.id === currentTrackId;
const localPauseResumeActive =
  sameTrack &&
  previousSession &&
  (previousSession.lastCommand === 'pause' || previousSession.lastCommand === 'resume') &&
  (previousSession.state === 'paused' || previousSession.state === 'playing');
const mergedState = localPauseResumeActive ? previousSession.state : status.state;
const mergedLastCommand = localPauseResumeActive ? previousSession.lastCommand : status.lastCommand;
```

**关键设计**：
- 仅在**同一首歌**时保护本地状态（`sameTrack` 检查）
- 切歌时（next/previous 返回新 track）正常使用后端状态
- 依赖 P0-A 设置的 `lastCommand` 标记判断是否有未同步的本地操作

## 5. 状态保护体系总览

P0-A 引入的 `lastCommand` 标记形成了一个三层状态保护体系：

```
用户按暂停
  │
  ├─ 层1: P0-A 立即设置 state='paused', lastCommand='pause'
  │        不发网络请求，不会被回滚
  │
  ├─ 层2: BUG-1 fix — onBuffering 检查 lastCommand
  │        buffering 回调不会覆盖 paused
  │
  └─ 层3: BUG-2 fix — mergeNowPlayingStatus 检查 lastCommand
           初始化/队列 fetch 不会覆盖 paused

用户按下一首
  │
  ├─ 不设 lastCommand='pause'/'resume'
  ├─ 正常走 optimistic + 网络确认
  └─ mergeNowPlayingStatus 检测到 sameTrack=false → 正常使用后端状态
```

## 6. 文件变更清单

| 文件 | 优化项 | 变更类型 |
|------|--------|----------|
| `src/contexts/PlayerContext.tsx` | P0-A, P0-B, P1-E, BUG-1 | 修改 |
| `src/hooks/useAudioPlayer.ts` | P2-F | 修改 |
| `src/components/player/NowPlayingScreen.tsx` | P0-B, P1-C, P1-D | 修改 |
| `src/components/player/MiniPlayer.tsx` | P0-B, P1-C | 修改 |
| `src/features/player/index.ts` | BUG-2 | 修改 |

**新增导出：**
- `usePlaybackProgress()` hook — 从 `PlayerContext.tsx` 导出
- `subscribeProgress` / `getProgressSec` — PlayerContextValue 接口新增字段

**无新文件、无新依赖、无后端修改。**

## 7. 性能影响评估

### 7.1 Re-render 频率对比

| 组件 | 优化前 | 优化后 | 降幅 |
|------|--------|--------|------|
| MiniPlayer | 每 500ms (进度 tick) | 每 500ms (仅自身) | 影响范围从全树缩小到自身 |
| NowPlayingScreen | 每 500ms (进度 tick) | 每 500ms (仅自身) | 同上 |
| BrowseScreen | 每 500ms (被动) | 仅 session 变化时 | **降低 ~99%** |
| LibraryScreen | 每 500ms (被动) | 仅 session 变化时 | **降低 ~99%** |
| SearchScreen | 每 500ms (被动) | 仅 session 变化时 | **降低 ~99%** |

### 7.2 Native Bridge 调用对比

| 操作 | 优化前 | 优化后 |
|------|--------|--------|
| pause/resume | `controlAudio` + `requestPlaybackControl` + `mergeNowPlayingStatus` → 2 次 bridge | `controlAudio` → 1 次 bridge |
| 进度条拖动 | `seekTo` 每帧 (~60 次/秒) | `throttledSeek` (~12 次/秒) |
| 500ms 进度 tick | `setPlaybackSession` → 全树 reconciliation | `notifyProgressListeners` → 2 组件 `setState` |

### 7.3 内存分配对比

| 来源 | 优化前 | 优化后 |
|------|--------|--------|
| NowPlayingScreen StyleSheet | 60+ 样式对象/render | 60+ 样式对象/theme 变化 |
| MiniPlayer StyleSheet | 12 样式对象/render | 12 样式对象/theme 变化 |
| Context value 对象 | 每 500ms 新对象 | 仅 session 变化时新对象 |

## 8. 兼容性

### 8.1 平台兼容

所有方案均为纯 React / React Native 标准 API，iOS 和 Android 完全兼容。

### 8.2 向后兼容

- `session.progressSec` 字段仍存在于 `PlaybackSession` 类型中，但不再实时更新。
- 消费者如需实时进度，应使用 `usePlaybackProgress()` hook。
- `PlayerContextValue` 新增 `subscribeProgress` 和 `getProgressSec`，不影响现有消费者。
- `mergeNowPlayingStatus` 行为变更：同一首歌 + `lastCommand` 为 pause/resume 时优先保留本地状态。切歌场景不受影响。

## 9. 变更日志

| 版本 | 日期 | 变更内容 |
|------|------|----------|
| v1.0 | 2026-02-14 | 初始版本：6 项播放链路优化（P0-A/B, P1-C/D/E, P2-F） |
| v1.1 | 2026-02-14 | 补充高延迟网络 bug 修复（BUG-1 onBuffering、BUG-2 mergeNowPlayingStatus） |
