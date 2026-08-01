# Research: TV Music Client V1 API Contract

- Query: 调研 Trim Music HTTP API，回答认证与 token 生命周期、用户/歌单/歌曲详情、播放与转码、歌词时间轴、漫游/随机/推荐、错误、分页/缓存，以及 TV 客户端 V1 的服务端缺口。
- Scope: internal（`docs/API.md` + `.detail/trim-music-v0.9.16` 源码快照）
- Date: 2026-07-31

## Findings

### 1. 结论摘要

现有 API 足以做出“密码登录 -> 我的歌单 -> 歌单歌曲 -> 单曲/连续播放 -> 漫游 -> 原始 LRC 歌词”的基础 V1，但客户端不能只按文档表面接入：

1. 普通用户 token 实际是 **30 天绝对有效期**，登录响应却不返回过期时间，也没有 refresh endpoint。相同 `user + deviceId` 再登录会立即替换旧 token；每用户最多 20 个活跃设备 token。
2. 原始流支持标准 Range 与条件请求；HLS 转码可解决 TV 解码兼容性和 CUE 分轨问题。但服务端没有公布可用转码 codec/profile，`channel` 参数在实现中被明确忽略，heartbeat 的时间单位/频率也没有契约。
3. **CUE 歌曲不能直接按 `/track/stream` 播放**：直流返回整个底层音频文件；只有转码路径应用 CUE 起止偏移。客户端必须按 `isCue` 强制走 HLS，或由服务端新增播放源协商接口。
4. 歌词接口返回未经解析的 `content: string`；`isLRC` 只是正则检测结果，没有逐行时间轴。V1 要么在客户端实现并测试 LRC parser，要么让服务端返回标准化 timeline。
5. “漫游”是随机队列，不是个性化推荐。状态只在进程内保存，空闲 24 小时过期，服务重启即丢失；候选单轮最多 20,000 首，耗尽后可重复。
6. 错误是多层模型：认证失败通常是 HTTP 401 + `code=99999`；业务失败通常 HTTP 200 + 非零 `code`；转码媒体服务失败甚至可能是 HTTP 200 + `code=0` + `data.status="failed"`。文件接口还存在 200/206/304/400/401/416/502 等分支。

### 2. 基础协议与响应包络

- 所有客户端 API 以 `/music/api/v1` 为前缀。来源：`docs/API.md:1-3`；路由实现在 `.detail/trim-music-v0.9.16/core/web/route.go:27-164`。
- JSON 接口统一返回 `{ code, msg, data }`，业务成功为 `code=0`；文件、原始音频、HLS、封面不保证此包络。来源：`docs/API.md:108-120`。
- 时间默认 Unix 秒；歌曲 `duration` 和歌词 `offset` 是毫秒。转码 `output.start/end` 例外，单位是秒。来源：`docs/API.md:122-128,442-452`。
- 客户端必须保留字段名的大小写差异，例如 `guid`、`trackGUID`、`artistGUID`、`resourceGUID`。来源：`docs/API.md:127-139`。
- 仓库未发现 OpenAPI/Swagger schema；当前可执行契约由 Markdown 和 Go request/VO 类型共同组成，生成客户端模型时不能假设存在机器可读 schema。

### 3. 认证方式与 token 生命周期

#### 3.1 登录与传递方式

| 能力 | 契约 |
| --- | --- |
| 密码登录 | `POST /user/password-login`，JSON `{ username, password, deviceId }`，返回 `{ userToken, user }`。`deviceId` 必填，长度 `1..200`。来源：`docs/API.md:13-42,176-184`；`.detail/trim-music-v0.9.16/core/model/req/user.go:8-12`。 |
| NAS OAuth 登录 | `POST /user/auth-login`，JSON `{ code, deviceId }`，返回同一 `LoginResult`。但 API 不提供 TV device-code/pairing 或取得 NAS OAuth code 的流程。来源：`docs/API.md:176-197`；`.detail/trim-music-v0.9.16/core/model/req/user.go:3-6`。 |
| 当前用户 | `GET /user/me`，返回 `User`，可用于冷启动校验 token 和恢复本地账号信息。来源：`docs/API.md:176-185,579-588`。 |
| token 传递 | 原样放入 `Authorization: <token>`，**没有 `Bearer ` 前缀**；也可用 `music-token=<token>` Cookie。来源：`docs/API.md:45-61,101-105`。 |
| 登出 | `POST /user/logout` 只删除当前请求使用的 token。来源：`docs/API.md:176-184`；`.detail/trim-music-v0.9.16/core/service/user.go:171-176`。 |

实现细节：

- 中间件优先读取 `music-token` Cookie，再读 `Authorization`。如果同时存在且 Cookie 已过期，新鲜的 header token 也不会被尝试；原生客户端应只使用一种方式。来源：`.detail/trim-music-v0.9.16/core/web/middleware.go:16-27`。
- 用户 token 是随机 GUID，持久化在 `user_token` 表；`ExpiredAt` 是实体字段。来源：`.detail/trim-music-v0.9.16/core/service/account/user_token.go:34-53`；`.detail/trim-music-v0.9.16/core/storage/entity/user_token.go:5-17`。
- token TTL 固定为 30 天；校验只检查是否过期并删除，没有滑动续期。来源：`.detail/trim-music-v0.9.16/core/service/account/user_token.go:137-142,183-211`。
- 创建 token 前会删除同一 `userID + deviceID` 的旧 token；每用户最多 20 个 token，超限时淘汰最早到期者。来源：`.detail/trim-music-v0.9.16/core/service/account/user_token.go:17-35,260-289`。
- 修改密码会删除该用户除当前设备外的 token，但当前设备 token 继续有效。来源：`.detail/trim-music-v0.9.16/core/service/user.go:190-201`。
- `LoginResult` 和 `/user/me` 都不含 `userTokenExpiredAt`，路由中也没有 refresh endpoint。来源：`docs/API.md:579-601`；`.detail/trim-music-v0.9.16/core/web/route.go:38-52`。

TV V1 当前可采用：生成并持久化一个稳定、随机的安装级 `deviceId`；安全存储 token；
冷启动先调 `/user/me`。只有用户 token 认证的 JSON API 返回 HTTP 401/`99999`/`120001`
时才清 token 并回登录页。query `tempToken` 的媒体 401 先用仍有效的用户 token 申请新
temp token，并重试同一 transcode session/generation，不重做 transcode；若换票接口也因
用户凭据返回 401，再以 `/user/me` 确认。raw 用户 Authorization 的媒体 401 同样先校验
`/user/me`，确认失效才登出。它能恢复，但 30 天后仍要求遥控器重新输入密码。

目标服务地址在任务背景和 API 示例中都是 `http://`。若部署没有反向代理 TLS，登录密码、用户 token 以及 URL 中的临时 token 都会明文经过局域网；客户端安全设计需明确只信任受控 LAN，或由部署侧提供 HTTPS。来源：`.trellis/tasks/07-31-tv-music-client-v1-plan/prd.md:9-13`；`docs/API.md:7-22`。

#### 3.2 临时播放 token

- `POST /user/temp-token` 请求：`usage="track-stream"`、`scopes` 为 `track:stream`/`track:hls` 子集、`resourceGUID=<track>`、`ttlSeconds`。token 绑定单曲 GUID 和 scope。来源：`docs/API.md:199-210`。
- TTL 合法范围实际为 `2..1800` 秒；`<=1` 或 `>1800` 不报错，而是回退 300 秒。来源：`.detail/trim-music-v0.9.16/core/service/req_normalizer.go:10-38`。
- 返回 `{ token, expiredAt }`，其中 `expiredAt` 是 Unix 秒。来源：`docs/API.md:601`；`.detail/trim-music-v0.9.16/core/service/user_temp_token.go:36-55`。
- 临时 token **不是一次性 token**：`consume` 校验后不删除，可在过期前供 manifest、分片和 Range 重连重复使用。来源：`.detail/trim-music-v0.9.16/core/service/user_temp_token.go:132-152`。
- 临时 token 只在进程内 map 中保存；服务重启或流量落到另一实例都会使它立即失效。来源：`.detail/trim-music-v0.9.16/core/service/user_temp_token.go:16-18,114-130`。
- 临时 token 请求若资源或 scope 不匹配，中间件仍统一映射为 HTTP 401 + `99999 INVALID TOKEN`，客户端看不到 `forbidden` 的细分原因。来源：`.detail/trim-music-v0.9.16/core/web/middleware.go:70-99`。
- HLS manifest 会把当时的 tempToken 写进子资源 URL，Media3 可能保留这些旧 URI。客户端
  不能只替换初始 manifest URL；query-token 模式必须在每个 DataSpec 发出前剥离旧值并
  注入 track 当前票据，401 换票后重试同一 transcode generation。

### 4. 用户、歌单与歌曲详情接口

#### 4.1 V1 最小 API 映射

| 场景 | 接口 | 主要返回 | 关键注意事项 |
| --- | --- | --- | --- |
| 恢复用户 | `GET /user/me` | `User { guid, name, role, lastAccessedAt, createdAt, updatedAt }` | 不返回 token 到期时间、设备信息或权限库列表。来源：`docs/API.md:183,581-588`。 |
| 我的歌单 | `GET /playlist/list` | `{ list: Playlist[], total }` | 无 `page/size` 参数，实际一次返回当前用户全部歌单。来源：`docs/API.md:401-405`；`.detail/trim-music-v0.9.16/core/service/playlist.go:157-171`。 |
| 批量补歌单计数 | `GET /playlist/batch-detail?guids=<逗号分隔>` | `{ list: (Playlist + trackCount)[] }` | `playlist/list` 本身没有 `trackCount`；该接口可避免列表逐项请求 detail。来源：`docs/API.md:404-407`。 |
| 歌单头部 | `GET /playlist/detail?guid=...` | `Playlist + trackCount` | 只允许访问自己的歌单；找不到或非本人均表现为资源不存在。来源：`docs/API.md:404-406`；`.detail/trim-music-v0.9.16/core/service/playlist.go:173-181,329-337`。 |
| 歌单歌曲 | `GET /track/playlist-detail/list?playlistGUID=...&page=1&size=...&sort=...` | `SortedPageList<TrackWithFavoriteAndAccess>` | 默认 `trackAddedAt,desc`；可传 `size=-1`；`accessStatus != 0` 的歌曲不可直接播放。来源：`docs/API.md:325-330,364-378,701`；`.detail/trim-music-v0.9.16/core/web/controller/music.go:127-136`。 |
| 单曲详情 | `GET /track/metadata?guid=...` | `{ audioSpec, track: Track + isFavorite + hasLyric }` | `track` 内又包含一个 `audioSpec`，即响应有重复音频规格；实现已有 TODO。来源：`docs/API.md:329-334,695-698`；`.detail/trim-music-v0.9.16/core/service/music.go:662-726`。 |
| 独立音频规格 | `GET /track/audio-info?guid=...` | 文档称 `AudioSpec` | 实现只填基础规格与 `format`，`path`/`size` 保持空/零；其 duration 取底层 audio file 时长，CUE 时不是分轨时长。优先使用 `/track/metadata`。来源：`docs/API.md:429-440,661-672`；`.detail/trim-music-v0.9.16/core/service/player.go:59-91`；`.detail/trim-music-v0.9.16/core/service/music.go:683-697`。 |
| 歌词 | `GET /lyric/list?trackGUID=...` | `{ list: Lyric[], preferred }` | 原始歌词文本；详见第 6 节。来源：`docs/API.md:334,716-740`。 |

公开路由没有任意用户的 `GET /user/detail`，V1 应把 `/user/me` 视为“用户详情”；管理员只有 `/user/list`。同样没有名为 `/track/detail` 的接口，客户端歌曲详情入口是 `/track/metadata`，播放规格可由 `/track/audio-info` 补充。路由证据：`.detail/trim-music-v0.9.16/core/web/route.go:41-52,80-103,141-147`。

#### 4.2 Track 对象对 TV 客户端有用的字段

- 展示与队列：`guid`, `title`, `coverId`, `duration`, `album`, `artists`, `genres`, `trackNo`, `discNo`, `isCue`。来源：`docs/API.md:674-690`。
- 播放决策：`audioSpec.codec/container/format/channel/sampleRate/bitDepth/bitrate`。`path` 是 NAS 文件系统路径，客户端不应依赖。来源：`docs/API.md:661-672`。
- 歌单歌曲额外返回 `accessStatus`：0 正常；1 音乐库目录不可访问；2 无音乐库权限；3 文件不存在；4 文件无权限。来源：`docs/API.md:692-701`。
- “播放全部”需要客户端本地维护队列。服务端没有普通歌单 queue/session API，也没有 playlist track 的稳定 revision/snapshot；`playlist.updatedAt` 在添加/移除歌曲的服务代码中没有更新。来源：`.detail/trim-music-v0.9.16/core/service/playlist.go:206-295`。

#### 4.3 “我的”资料库概览补充契约

用户后续明确要求“我的”展示歌手、专辑和音乐库，而不是重复歌单。V1 概览可使用：

| 区块 | 接口与字段 | 客户端边界 |
| --- | --- | --- |
| 歌手 | `GET /artist/list?page=1&size=6` -> `ArtistWithCounts { guid, name, coverId, trackCount, albumCount }` | 顶层列表支持分页但不支持 `size=-1`；使用返回的 `total/sort`，单独处理该区错误。来源：`docs/API.md:315-318,622-634`。 |
| 专辑 | `GET /album/list?page=1&size=6` -> `AlbumWithArtistsAndTrackCount { guid, name, coverId, releaseDate, artists, trackCount }` | 顶层列表支持分页但不支持 `size=-1`；封面请求 400，缺歌手/日期可为空。来源：`docs/API.md:319-322,636-650`。 |
| 音乐库 | `GET /shared-library/list` -> `SharedLibrary { guid, name, path, contentLastChangedAt, accessStatus, ... }` | UI 仅投影 `guid/name/contentLastChangedAt/accessStatus`；`path`、metadataPreference 等只留在 data 层，不进 UI、日志或诊断。来源：`docs/API.md:241-248,745-756`。 |

三个区块应并行、独立加载：一个接口失败时保留另外两个区块和设置入口，不把整个“我的”
替换为全屏错误。缓存与页面状态按 `(serverGUID,userGUID)` 隔离。

`SharedLibrary.accessStatus` 固定映射：0 正常/在线，1 路径不存在，2 路径无访问权限，
3 未获应用中心授权；TV 概览对 1-3 统一显示“不可用”，诊断可保留细分原因，未知值也按
不可用处理。来源：`.detail/trim-music-v0.9.16/core/constant/constant.go:6-9`。

PRD 推荐决策 D1 提议按主流 TV 内容带提供可操作浏览；只有本次方案评审确认 D1 后，
以下详情映射才进入 V1 的实施与验收范围：

- 歌手：`/artist/detail?guid=...`，专辑用 `/album/artist-detail/list`，歌曲用
  `/track/artist-detail/list`，两类列表都显式 `page/size=50`。
- 专辑：`/album/detail?guid=...` + `/track/album-detail/list?page=...&size=50`。
- 全部歌曲：`/track/list?page=...&size=50`；返回 `TrackWithFavorite` 而不是带
  `accessStatus` 的歌单歌曲模型，准备播放时仍需处理无权限/文件失效并跳过。
- 这些接口支持只读浏览和建立客户端普通队列，不需要任何 create/edit/delete/scan 接口。
  来源：`docs/API.md:315-329,622-715`。

### 5. 音频流 URL、Range、格式与转码

#### 5.1 原始音频流

- URL：`GET {base}/track/stream?guid=<track-guid>`。认证可用用户 header/Cookie，或追加 `&tempToken=<token>`。来源：`docs/API.md:63-88,431-440`。
- 文件响应使用 Go `http.ServeContent`，因此支持 Range 和标准条件请求；服务额外设置基于文件 hash 的强 ETag。来源：`.detail/trim-music-v0.9.16/core/web/controller/file_response.go:28-50,53-64`。
- 缓存头为 `private, max-age=86400, must-revalidate, no-transform`，并暴露 `Accept-Ranges, Content-Range, Content-Length, ETag, Last-Modified`。来源：`.detail/trim-music-v0.9.16/core/web/controller/file_response.go:11-14,28-34`。
- 正常响应需要处理 `200`、`206`、`304`、`416`；416 会带 `Content-Range: bytes */<total>`。来源：`docs/API.md:440`；`.detail/trim-music-v0.9.16/core/web/controller/file_response.go:16-25`。
- 明确的 suffix -> MIME 映射包括：mp3、flac、wav、ogg、opus、aac、m4a/m4b、aiff/aif、ape、wma、dsf、dff、dts、tta；未知格式返回 `application/octet-stream`。这只是响应 MIME 映射，不等于 TV 硬件或服务端转码器的兼容保证。来源：`.detail/trim-music-v0.9.16/core/service/player.go:350-382`。

**CUE 关键差异**：`/track/stream` 只解析到 `audioFile.Path` 并返回整个文件，没有应用 `StartOffsetMS/EndOffsetMS`。转码路径才把 CUE 的起止毫秒换算为秒并限定输出区间。来源：`.detail/trim-music-v0.9.16/core/service/player.go:94-106,141-165`。因此 V1 的决策规则至少应是：`isCue=true -> 禁止 direct stream -> transcode/HLS`。

#### 5.2 HLS 转码流程

1. 登录态调用 `POST /track/transcode`，body `{ guid, output: { start, end?, codec, bitrate?, channel? } }`；`start/end` 是秒。来源：`docs/API.md:433-452`。
2. 成功返回相对 URL `/music/api/v1/track/hls/{guid}/preset.m3u8`、`hlsTime` 和 `status="success"`。客户端需拼接 server origin。来源：`.detail/trim-music-v0.9.16/core/service/player.go:227-237`。
3. 对无法给每个分片加用户 header 的 TV 播放器，再创建含 `track:hls` scope 的临时 token，并在 manifest URL 追加 `tempToken`。代理会把同一 token 自动写入 manifest 中的 `.ts/.m4s/.mp4/.m3u8/.key/.vtt` 子 URL。来源：`.detail/trim-music-v0.9.16/core/pkg/client/trimmediasrv/resp_rewrite.go:16-33,44-86`。
4. 播放中调用 `/track/transcode/heartbeat`，停止时调用 `/track/transcode/quit`。来源：`docs/API.md:435-466`。

实现边界：

- `codec` 字符串未经白名单校验，原样传给 mediasrv；仓库没有公开 codec/profile 能力枚举。来源：`.detail/trim-music-v0.9.16/core/model/req/player.go:3-14`；`.detail/trim-music-v0.9.16/core/service/player.go:203-212`。
- `bitrate` 缺省传 0。`channel` 虽在公开 request 中，但实现明确注释为暂不使用，固定传 `-1`；文档对“可指定 channel”的表述不可作为有效能力。来源：`.detail/trim-music-v0.9.16/core/service/player.go:192-212`。
- 同一 `user + 登录 token 内的 deviceId + trackGUID` 只有一个转码 session；新转码会 quit 旧 session。session 只在内存中，服务重启即失效。来源：`.detail/trim-music-v0.9.16/core/service/player.go:20-57,130-135`。
- 对同一 track/profile，新旧 session 对外仍使用相同的 preset manifest 路径，代理内部才
  切换到当前 `session.PlayLink`，segment 文件名也可能重用。因此客户端缓存必须在每次
  transcode create 成功后换一个本地 session generation；不能仅靠 path/profile 跨 session
  复用 HLS 分片。来源：`.detail/trim-music-v0.9.16/core/service/player.go:227-237,299-336`。
- HLS 访问也用该 session key，所以 `deviceId` 必须稳定；临时 token 会继承创建它的用户 token 的 deviceId。来源：`.detail/trim-music-v0.9.16/core/service/user_temp_token.go:39-50,75-81`；`.detail/trim-music-v0.9.16/core/service/player.go:315-318`。
- heartbeat 的 `timestamp` 是 `float64` request，但向 mediasrv 传递时截断为 `int64`；文档没有说明单位、频率、容错窗口或 `hlsTime` 语义。来源：`.detail/trim-music-v0.9.16/core/model/req/player.go:20-23`；`.detail/trim-music-v0.9.16/core/service/player.go:269-296`。
- 临时 token 最长 30 分钟。长曲目或网络重连发生在过期后时，后续 HLS 分片/Range 请求会 401；当前没有 token renew 或不中断换票协议。
- mediasrv 拒绝 codec 等转码失败时，HTTP 和外层 `ApiResult.code` 仍可能成功，失败放在 `data.status/errno/errmsg`。来源：`.detail/trim-music-v0.9.16/core/service/player.go:213-223`。
- create 不是可安全重复的 POST：底层调用可等待约 50 秒，而相同 session key 的新结果会
  覆盖映射。客户端若在请求已发送但响应丢失后立即重试，可能创建/泄漏两个 PlayLink；
  因此只在确认请求未发送时自动重试，其他超时先等待服务端上界并受控清理。服务端最好
  增加 idempotency key 与当前 session status/reconcile。来源：
  `.detail/trim-music-v0.9.16/core/pkg/client/trimmediasrv/client.go:20`；
  `.detail/trim-music-v0.9.16/core/service/player.go:31-42,227-237`。

#### 5.3 建议的 V1 播放选择

- 非 CUE 且 TV 原生播放器明确支持 `audioSpec.codec + container`：优先原始流，使用 Range seek。
- CUE 或设备不支持源格式：走转码 HLS。
- 在服务端 capability 契约补齐前，不应把某个 codec（例如 AAC）硬编码为“服务器一定支持”；这需要在目标 NAS 实机验证。
- 播放失败的降级顺序建议为：direct 初始化/解码失败 -> 请求 HLS；HLS manifest/segment
  的 404 或 session-not-found `100005` -> 重新 transcode；query tempToken 的 401 -> 只换
  temp token 并重试同一 generation；raw 用户 Authorization 的 401 -> `/user/me`，确认用户
  token 失效才登出。长曲目临时票据过期本身不得触发新 transcode。

### 6. 歌词接口与时间轴格式

- `GET /lyric/list?trackGUID=...` 返回多个候选歌词：`guid, source, content, createdAt, updatedAt, isLRC, offset`，并以 `preferred` 指出默认 lyric GUID。来源：`docs/API.md:716-740`。
- `source`：1 内嵌、2 同目录 sidecar、3 在线刮削、4 用户关联。来源：`docs/API.md:740`。
- `content` 是 UTF-8 化后存储并原样读出的完整文本，不提供 `lines`、`startMs` 或 `endMs`。来源：`.detail/trim-music-v0.9.16/core/service/filestore/lyric.go:16-32,44-50`；`.detail/trim-music-v0.9.16/core/service/lyric.go:100-124`。
- `isLRC` 仅表示至少一行匹配 `[(1..3 位分钟):(00..59 秒)(可选 ./: 1..3 位小数)]`。它不解析 metadata tag、同一行多时间戳、逐字歌词、翻译行或 end time。来源：`.detail/trim-music-v0.9.16/core/pkg/lrc/lrc.go:9-41`。
- `offset` 是用户对该歌词的毫秒偏移，可正可负；事件写入时会钳制在 `[-track.duration, +track.duration]`。来源：`docs/API.md:127,716-724`；`.detail/trim-music-v0.9.16/core/service/event.go:107-119,142-153`。这些证据没有定义正值使歌词提前还是延后，仓库又缺前端源码，因此符号语义仍是待实测契约。
- 用户显式选择过的 lyric 优先；无显式偏好时，实际排序为同步 LRC优先，然后 sidecar > embedded > scraped/user-linked，最后按更新时间倒序。来源：`.detail/trim-music-v0.9.16/core/service/lyric.go:45-98,127-160`。
- 若当前无歌词且音乐库开启 `autoDownloadLyric`，一次 `lyric/list` 会同步触发云端抓取后再次查询；接口没有 `pending/none/failed` 状态，首次调用可能明显变慢。来源：`.detail/trim-music-v0.9.16/core/service/lyric.go:29-43`；`.detail/trim-music-v0.9.16/core/service/scanner/metadata_cloud_scrape.go:294-358`。
- `event/report` 可写歌词偏好和 offset，但批量内单个事件失败会被记录后跳过，整个接口仍返回成功；客户端无法确认某个偏好是否真正落库。来源：`docs/API.md:543-563`；`.detail/trim-music-v0.9.16/core/service/event.go:20-38`。

V1 若客户端解析，应将后端原始 `content` 保留为 source-of-truth，解析成按 `startMs` 稳定
排序的本地 timeline。M0/M4 必须用专用歌词分别设置 `+1000/-1000 ms`，观察现有 Web
播放显示是提前还是延后并恢复原值；在结果记录前不得冻结加减公式。无 LRC 时把全文作为
静态歌词，而不是把 `isLRC=false` 当错误。

### 7. 漫游、随机与推荐能力

- 公开接口仅有 `roam-start`、`roam-previous`、`roam-next`；客户端保存每首返回的 `roamId`，下一次把当前节点作为 `relativeRoamId`。来源：`docs/API.md:380-388,731-737`。
- `roam-start` 会重置该 `userID + 请求 deviceId` 的旧 session，然后预取 current 和 next。无可访问音乐库/无候选时返回成功且 `data=null`，而不是一个带原因的空窗口。来源：`.detail/trim-music-v0.9.16/core/service/roam.go:36-80`。
- 候选是用户有权访问、未被文件删除/管理员删除的 track；数据库随机取最多 20,000 条，内存再 shuffle。来源：`.detail/trim-music-v0.9.16/core/storage/dao/track.go:436-451`；`.detail/trim-music-v0.9.16/core/service/roam.go:343-368`。
- 当前一轮候选耗尽会重新查询并洗牌；排除已播歌曲的逻辑被注释掉，因此跨轮可以重复，且 >20,000 首的库不保证一轮覆盖全部歌曲。来源：`.detail/trim-music-v0.9.16/core/service/roam.go:513-532`。
- previous/next 会复用已经生成的链表节点，并跳过后来变得不可播放的歌曲。来源：`.detail/trim-music-v0.9.16/core/service/roam.go:249-340`。
- session 在进程内保存，按最后访问时间 24 小时过期；服务重启、切换实例或再次调用 start 都会丢失旧 `roamId`。来源：`.detail/trim-music-v0.9.16/core/service/roam.go:20-33,401-433,549-554`。
- 未发现 related tracks、相似歌手、基于历史/收藏的推荐、每日推荐、情绪/流派种子或推荐理由接口。V1 文案应称“随机漫游”，不能声称“个性化推荐”。
- 普通歌单队列与漫游队列彼此无服务端关联；“退出漫游不破坏普通播放上下文”必须由客户端保存两套 queue/context 实现。

### 8. 错误模型

#### 8.1 客户端判断顺序

1. 先看 HTTP status。401 视为认证票据失效；400/416/502 等按文件或代理错误处理。
2. 若 `Content-Type` 是 JSON，再解析 `ApiResult` 并要求 `code == 0`。
3. 对 transcode/heartbeat/quit，还必须要求 `data.status == "success"`。
4. 对文件响应按 200/206/304/416 处理，不尝试把音频正文解析成 JSON；但 200 也可能是业务错误 JSON，因此仍要检查 `Content-Type`。

该顺序与文档建议一致。来源：`docs/API.md:818-855`。

#### 8.2 实现中的具体分层

| 情况 | HTTP | JSON code / 其他 |
| --- | ---: | --- |
| 用户 token 缺失、过期、无效 | 401 | `99999 INVALID TOKEN`。来源：`.detail/trim-music-v0.9.16/core/web/middleware.go:53-61`。 |
| 临时 token 过期、scope/resource 不匹配 | 401 | 同样统一为 `99999`。来源：`.detail/trim-music-v0.9.16/core/web/middleware.go:70-90`。 |
| 一般业务错误 | 200 | 非零 code，`data=null`。来源：`.detail/trim-music-v0.9.16/core/web/controller/common.go:31-51`。 |
| Gin JSON binding/validation 错误 | 200 | 当前不是 BizError，通常落入 `100001 unknown error`，而不是文档中的 `100002 invalid arguments`。来源：`.detail/trim-music-v0.9.16/core/web/controller/common.go:31-49`，调用示例 `.detail/trim-music-v0.9.16/core/web/controller/player.go:34-39`。 |
| 原始音频业务错误（无权限/找不到） | 200 | JSON 错误，因为 controller 在发送文件前调用统一 `fail`。来源：`.detail/trim-music-v0.9.16/core/web/controller/player.go:23-31`。 |
| 封面 ID/路径解析失败 | 400 | 空响应，不走统一 JSON 错误。来源：`.detail/trim-music-v0.9.16/core/web/controller/static.go:20-38`。 |
| HLS 上游代理失败 | 502 | ReverseProxy 直接写 status。来源：`.detail/trim-music-v0.9.16/core/pkg/client/trimmediasrv/client.go:159-175`。 |
| mediasrv 拒绝转码 | 200 | 外层 `code=0`，`data.status="failed"` + `errno/errmsg`。来源：`.detail/trim-music-v0.9.16/core/service/player.go:213-223`。 |

文档列出的公共业务码见 `docs/API.md:818-853`，主要包括 100002 参数错误、100003 管理员权限、100004 禁止访问、100005 不存在、120001 重新登录、120002 账号禁用、130001 CUE 偏移缺失等。对 TV V1，至少需要建立：`99999/120001 -> 回登录`、`100004 -> 无权访问`、`100005 -> 内容已下线/重新取队列`、`130001 -> 跳过并报告不可播放` 的稳定映射。

### 9. 分页、排序与缓存提示

#### 9.1 分页

- 文档默认 `page=1,size=50`，响应只有 `list,total`，排序列表再加 `sort`；不返回当前 page、size、hasMore 或 next cursor。来源：`docs/API.md:122-135`。
- 实现把 `page <= 0` 或 `page > 2000` 回退为 1，把 `size <= 0` 或 `size > 2000` 回退为 50。来源：`.detail/trim-music-v0.9.16/core/web/controller/common.go:54-65`。
- `size=-1` 明确支持的客户端列表：全部歌曲、艺术家/专辑/流派/歌单内歌曲、收藏歌曲、播放历史。来源：`.detail/trim-music-v0.9.16/core/web/controller/music.go:57-136`；`.detail/trim-music-v0.9.16/core/web/controller/user_preference.go:39-80`。
- 艺术家/专辑/流派顶层列表不支持 `size=-1`；歌单列表完全不分页。搜索接口不接受 page/size，建议最多 5 条、完整搜索最多 500 条。来源：`docs/API.md:315-329,401-405,416-427`。
- 全部是 offset pagination，没有 snapshot/cursor。歌单在翻页过程中发生增删时可能重复或漏项；`size=-1` 虽方便“播放全部”，但大歌单会产生无上限响应、解析和内存成本。
- 非法 sort 会回退默认值，实际 sort 通过 `data.sort` 回显；客户端应使用回显值作为当前排序状态。来源：`docs/API.md:364-378`。

#### 9.2 缓存

- 原始流/原始下载：private 缓存 1 天、must-revalidate、ETag/Last-Modified/Range。来源：`.detail/trim-music-v0.9.16/core/web/controller/file_response.go:11-14,28-50`。
- 封面：`public, max-age=2592000, immutable`（30 天），ETag 为 `"<cover-guid>-<size>"`。来源：`.detail/trim-music-v0.9.16/core/web/controller/static.go:35-42`。
- 封面接口本身要求登录，却允许 public shared cache，且未设置 `Vary: Authorization`。如果前面存在共享代理/CDN，这可能把一个用户有权访问的封面复用给另一个用户；服务端应确认封面是否被视为非敏感，或改为 `private`。
- JSON 列表/详情接口未发现 `Cache-Control`、ETag、Last-Modified 或 revision。TV 客户端只能使用自己的 stale-time/磁盘缓存策略，并在 401、资源不存在或显式刷新时失效。

### 10. TV V1 当前可落地的数据流

1. 登录：`password-login` -> 安全保存 user token；启动时 `/user/me` 校验。
2. 浏览：`playlist/list`，需要歌曲数时用一次 `playlist/batch-detail` 补齐；进入详情并行请求 `playlist/detail` 和 `track/playlist-detail/list`。
3. 播放全部：按服务端回显 sort 构造本地 queue，过滤/跳过 `accessStatus != 0`；按需分页预取，避免默认使用 `size=-1` 造成超大响应。
4. 单曲：列表对象已含完整 Track/audioSpec；需要 `hasLyric` 时再请求 `track/metadata`，不要每次重复取 `audio-info`。
5. 播放源：非 CUE 且本机支持时 direct stream；否则 transcode -> temp HLS token -> manifest。播放器初始化后进入大屏播放页。
6. 歌词：并行请求 `lyric/list`，选择 `preferred`；本地解析 LRC 并应用 offset；空列表显示“无歌词”，失败显示可重试状态。
7. 播放记录：成功开始播放后可批量上报 `track_play`；不能把 event/report 的整体成功当作单事件持久化确认。
8. 漫游：单独保存 roam context；`roam-start` 后按 `roamId` 前后移动；旧 roamId 返回参数错误时重新 start；退出后恢复普通 queue 和 index。

### 11. V1 缺口与建议服务端补充

#### 11.1 开发冻结前必须澄清或修复

| 优先级 | 缺口 | 建议契约 |
| --- | --- | --- |
| P0 | 没有公开的转码 codec/profile、默认 bitrate、heartbeat 单位/频率、`hlsTime` 语义。客户端无法做确定性兼容矩阵。 | 新增 `GET /player/capabilities`（或扩展公开 `/sys/config`），返回 direct MIME/format、`transcodeProfiles[]`、默认值、最大临时 token TTL、heartbeat interval 与 timestamp unit。 |
| P0 | CUE direct stream 会返回整张底层文件；客户端很容易误播。 | 最好新增 `POST /track/playback-source`，输入 track + device capabilities + start position，服务端返回 `{ mode: direct|hls, url, contentType, expiresAt, heartbeat }` 并保证 CUE 自动裁剪。至少在文档中明确 `isCue -> transcode only`。 |
| P0 | `channel` 公开参数实际被忽略，`audio-info` 的 path/size 与文档类型不一致。 | 修正文档或实现；在能力接口只公布真正支持的参数，不让客户端依赖无效字段。 |
| P0 | 用户 token 30 天过期但客户端拿不到 expiredAt，也无 refresh。 | 登录结果增加 `userTokenExpiredAt`；新增受控 refresh/rotate endpoint。若不做 refresh，至少明确绝对过期与重新登录错误。 |
| P0 | 当前目标 origin 使用 HTTP；密码和 bearer-equivalent token 没有传输层保护。 | 支持 HTTPS/可信证书或在 NAS 前置 TLS；如果产品明确只支持受控 LAN，也要在配对和威胁模型中写清边界。 |
| P0 | HTTP/code/data.status 三层错误不一致，binding 错误落到 unknown；文件端点错误形态不统一。 | 发布规范化错误表；binding 映射 100002；为 JSON 错误设置稳定 HTTP 策略；转码失败要么提升为非零业务 code，要么把 `MediaTaskResult` 明确定义为第三层。 |

#### 11.2 强烈建议的服务端增强

| 优先级 | 缺口 | 建议契约 |
| --- | --- | --- |
| P1 | 电视输入密码成本高；NAS OAuth code 的获取流程不在此 API 内。 | 增加 TV device-code/二维码配对：创建短码、轮询授权、确认后下发设备 token；同时提供 token 撤销/设备列表。 |
| P1 | 临时 token 仅内存、最多 30 分钟；长曲目/重连和多实例不稳定。 | 使用可校验的签名票据或共享存储；提供不中断 renew，或让 `playback-source` 返回可覆盖整首时长的播放票据。 |
| P1 | 原始歌词没有标准 timeline，且“无歌词/抓取中/抓取失败”不可区分。 | 扩展 `/lyric/list` 或新增 `/lyric/timeline`：`format`, `status`, `lines[{startMs,endMs?,text,translation?}]`, `offsetMs`, `retryAfter`；保留 raw content 兼容。 |
| P1 | 播放全部缺少稳定、只含可播放项的歌单快照；offset 翻页会漂移。 | 新增 `/playlist/playback-snapshot?guid=...`，返回 `revision`, 有序 track GUID/必要展示字段、不可播放原因和 continuation cursor；或至少在 playlist 增删时更新 revision/updatedAt。 |
| P1 | 漫游空结果只给 `data=null`，session 不可恢复，也没有推荐语义。 | 返回 `sessionId/expiresAt/emptyReason`；若产品未来需要“推荐”，应另建推荐 endpoint，不要复用随机 roam 命名。 |
| P1 | 服务地址发现与 TV 配对不在 HTTP API 中；`/sys/config` 只能在已知 base URL 后使用。 | 明确 mDNS/局域网发现协议，或让二维码/配对信息包含完整 origin 与服务器 GUID。 |

若后端暂时零改动，V1 仍能启动开发，但必须把以下兼容规则写进客户端设计和测试：稳定
`deviceId`、用户 401 校验后重登、媒体 tempToken 401 只换票、CUE 强制 HLS、transcode
`status` 二次判断、HLS 404 才重建 session、roam session 失效重启、LRC 本地解析、文件
响应按 Content-Type 分流。

### 12. Files Found

- `docs/API.md` - 客户端公开 HTTP API、请求/响应类型、错误码和基本缓存/Range 声明。
- `.detail/trim-music-v0.9.16/core/web/route.go` - v1 路由全集和认证中间件挂载位置。
- `.detail/trim-music-v0.9.16/core/web/middleware.go` - Cookie/header 用户 token 与临时 token 的真实认证行为。
- `.detail/trim-music-v0.9.16/core/service/account/user_token.go` - 30 天 TTL、同设备替换、20 设备上限和 token 删除逻辑。
- `.detail/trim-music-v0.9.16/core/service/user_temp_token.go` - 临时 token 的内存存储、资源/scope/过期校验。
- `.detail/trim-music-v0.9.16/core/service/req_normalizer.go` - 临时 token TTL 与 scope 标准化。
- `.detail/trim-music-v0.9.16/core/service/playlist.go` - 用户歌单 ownership、非分页列表和 track 变更行为。
- `.detail/trim-music-v0.9.16/core/service/music.go` - 单曲 metadata、音频规格和 `hasLyric` 构造。
- `.detail/trim-music-v0.9.16/core/service/player.go` - direct stream、CUE、转码 session、heartbeat 和 HLS proxy。
- `.detail/trim-music-v0.9.16/core/web/controller/file_response.go` - Range、ETag、缓存和 416 响应。
- `.detail/trim-music-v0.9.16/core/pkg/client/trimmediasrv/resp_rewrite.go` - HLS 子资源临时 token 注入。
- `.detail/trim-music-v0.9.16/core/service/lyric.go` - 歌词候选、偏好、offset 和按需抓取。
- `.detail/trim-music-v0.9.16/core/pkg/lrc/lrc.go` - 当前所谓 LRC 的唯一格式检测规则。
- `.detail/trim-music-v0.9.16/core/service/roam.go` - 随机队列、节点窗口和 24 小时内存 session。
- `.detail/trim-music-v0.9.16/core/storage/dao/track.go` - 漫游最多 20,000 个随机候选的 SQL。
- `.detail/trim-music-v0.9.16/core/web/controller/common.go` - JSON success/fail 包络和分页回退。
- `.detail/trim-music-v0.9.16/core/web/controller/static.go` - 封面缓存、ETag 与异常 HTTP 形态。

### 13. Code Patterns

- API 层不是 REST HTTP status 驱动，而是 `HTTP 200 + ApiResult.code` 驱动；认证中间件和文件代理是例外。见 `.detail/trim-music-v0.9.16/core/web/controller/common.go:18-51`。
- 播放鉴权把“不便设置 header 的媒体播放器”视为一等场景：临时 token 放 query，并自动传播到 HLS children。见 `.detail/trim-music-v0.9.16/core/web/middleware.go:70-99` 和 `.detail/trim-music-v0.9.16/core/pkg/client/trimmediasrv/resp_rewrite.go:44-86`。
- 多个播放相关状态容器是单进程内存 map（temp token、transcode session、roam session），没有跨实例/重启持久性。见 `.detail/trim-music-v0.9.16/core/service/user_temp_token.go:16-18`、`.detail/trim-music-v0.9.16/core/service/player.go:20-32`、`.detail/trim-music-v0.9.16/core/service/roam.go:24-33`。
- Track 列表对象偏“全量 DTO”：每项携带 artists/album/genres/audioSpec，适合首屏少请求，但大歌单 `size=-1` 的网络与反序列化成本高。类型见 `.detail/trim-music-v0.9.16/core/model/vo/vo.go:91-118`。

### 14. External References And Versions

- 服务实现证据来自仓库快照目录 `.detail/trim-music-v0.9.16`，版本标识为 `0.9.16`。
- 未使用外部网络资料；本题的权威来源是仓库内 `docs/API.md` 与对应服务实现。
- 运行时可通过公开 `GET /sys/config` 的 `serverVersion`、`mediasrvVersion` 对照目标 NAS；字段定义见 `docs/API.md:603-609`。

### 15. Related Specs

- `.trellis/spec/guides/cross-layer-thinking-guide.md:19-51` - 要求明确 API/客户端边界上的输入、输出和错误；本文件重点记录了这些不一致。
- `.trellis/spec/backend/error-handling.md:1-51` - 当前仍是待填写模板，没有项目级 HTTP/API 错误规范可补充本 API 的歧义。
- `.trellis/spec/frontend/type-safety.md:1-51` - 当前仍是待填写模板，没有现成的 DTO 解码/运行时校验约定。
- `.trellis/spec/frontend/state-management.md:1-50` - 当前仍是待填写模板，没有现成的 server-state 缓存约定。

## Caveats / Not Found

- 本研究是静态阅读 `docs/API.md` 和 v0.9.16 源码快照，没有向 `10.0.0.115:5666` 发请求，也没有验证目标 NAS 当前运行版本；上线前应做一轮真实响应录制，尤其验证 codec、HLS、Range、CUE、token 过期和错误 Content-Type。
- 未找到 OpenAPI/Swagger、服务端 playback capability endpoint、refresh token、TV device-code/pairing、播放队列 snapshot、标准化 lyric timeline 或个性化 recommendation endpoint。
- mediasrv 是通过 Unix socket 调用的外部组件；仓库代码只透传 codec，无法从本仓库可靠推出其完整编码能力。
- `hlsTime`、heartbeat `timestamp` 的单位/调用间隔、转码 session 的上游超时均未在公开文档或所读代码中形成完整契约，必须由服务端维护者确认或通过实机测试补证。
- “推荐”若仅指 V1 随机漫游，现有 API 可用；若产品文案承诺个性化、相似内容或根据历史学习，现有能力不成立。
