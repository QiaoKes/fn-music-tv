# Trim Music HTTP API

本文档描述客户端可调用的 HTTP API。所有 API 路径都以 `/music/api/v1` 为前缀。

## 1. 快速开始

假设服务地址为：

```bash
export MUSIC_API_BASE='http://<host>:<port>/music/api/v1'
```

### 1.1 密码登录

```bash
curl -sS -X POST "$MUSIC_API_BASE/user/password-login" \
  -H 'Content-Type: application/json' \
  -d '{
    "username": "admin",
    "password": "<password>",
    "deviceId": "my-client"
  }'
```

成功时 `data.userToken` 是后续请求使用的用户令牌：

```json
{
  "code": 0,
  "msg": "success",
  "data": {
    "userToken": "<token>",
    "user": {
      "guid": "<user-guid>",
      "name": "admin",
      "role": "admin",
      "lastAccessedAt": 0,
      "createdAt": 0,
      "updatedAt": 0
    }
  }
}
```

### 1.2 携带令牌调用

`Authorization` 的值直接填写 `userToken`，不要添加 `Bearer ` 前缀：

```bash
export MUSIC_TOKEN='<data.userToken>'

curl -sS "$MUSIC_API_BASE/track/list?page=1&size=50&sort=createdAt,desc" \
  -H "Authorization: $MUSIC_TOKEN"
```

也可以使用名为 `music-token` 的 Cookie：

```bash
curl -sS "$MUSIC_API_BASE/user/me" \
  --cookie "music-token=$MUSIC_TOKEN"
```

### 1.3 获取并播放音频

直接使用用户令牌：

```bash
curl -L "$MUSIC_API_BASE/track/stream?guid=<track-guid>" \
  -H "Authorization: $MUSIC_TOKEN" \
  -o track.audio
```

或先创建临时令牌，再把返回的 `data.token` 放到 `tempToken` 查询参数中：

```bash
curl -sS -X POST "$MUSIC_API_BASE/user/temp-token" \
  -H "Authorization: $MUSIC_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "usage": "track-stream",
    "scopes": ["track:stream", "track:hls"],
    "resourceGUID": "<track-guid>",
    "ttlSeconds": 300
  }'

curl -L "$MUSIC_API_BASE/track/stream?guid=<track-guid>&tempToken=<temp-token>" \
  -o track.audio
```

## 2. 通用约定

### 2.1 鉴权与权限

| 标记 | 含义 |
| --- | --- |
| 公开 | 不需要令牌 |
| 登录 | 需要有效的用户令牌 |
| 管理员 | 需要管理员用户令牌 |
| 临时/登录 | 可使用对应资源的临时令牌，或用户令牌 |

用户令牌可通过以下任一种方式传入：

- 请求头：`Authorization: <userToken>`
- Cookie：`music-token=<userToken>`

令牌缺失、无效或用户受限时，HTTP 状态通常为 `401`。权限不足等业务错误通常仍返回 HTTP `200`，应以响应体 `code` 判断是否成功。

### 2.2 JSON 响应

除文件、音频流、HLS 和封面接口外，响应均为：

```ts
type ApiResult<T> = {
  code: number;
  msg: string;
  data: T | null;
};
```

成功时 `code` 为 `0`；没有业务数据的成功操作，其 `data` 为 `null`。

### 2.3 分页、排序与时间

- 分页参数为 `page` 和 `size`，默认分别为 `1` 和 `50`。
- 非法的 `page` 或 `size` 会回退到默认值。部分歌曲列表支持 `size=-1` 表示返回全部。
- 排序格式为 `<field>,<direction>`，其中 `direction` 为 `asc` 或 `desc`。
- 未特别说明时，时间字段是 Unix 秒时间戳；`duration` 和歌词 `offset` 使用毫秒。
- `GUID`、`guid`、`trackGUID` 等值均为资源 GUID，字段名大小写必须保持文档中的形式。

分页响应有两种形式：

```ts
type PageList<T> = { list: T[]; total: number };
type SortedPageList<T> = { list: T[]; total: number; sort: string };
```

### 2.4 参数记号

接口表中的参数前缀：`Q` 为 query，`P` 为 path，`J` 为 JSON body，`F` 为表单字段，`M` 为 multipart 字段。带 `*` 的参数必须提供。

## 3. 初始化与系统

| 方法 | 路径 | 权限 | 参数 | `data` |
| --- | --- | --- | --- | --- |
| GET | `/initialization/state` | 公开 | 无 | `InitializationState` |
| POST | `/initialization/prepare` | 公开 | J `code*`, `deviceId*` | `InitializationPrepare` |
| POST | `/initialization/confirm` | 公开 | `InitializationConfirmRequest` | `LoginResult` |
| GET | `/sys/config` | 公开 | 无 | `SystemConfig` |
| GET | `/sys/info` | 登录 | 无 | `SystemInfo` |

`POST /initialization/prepare`：

```json
{
  "code": "<nas-oauth-code>",
  "deviceId": "my-client"
}
```

`POST /initialization/confirm`：

```json
{
  "sessionId": "<session-id>",
  "user": {
    "name": "admin",
    "password": "<password>"
  },
  "server": {
    "name": "My Music",
    "lang": "zh-CN"
  }
}
```

## 4. 登录与用户

| 方法 | 路径 | 权限 | 参数 | `data` |
| --- | --- | --- | --- | --- |
| POST | `/user/auth-login` | 公开 | J `code*`, `deviceId*` | `LoginResult` |
| POST | `/user/password-login` | 公开 | J `username*`, `password*`, `deviceId*` | `LoginResult` |
| POST | `/user/logout` | 登录 | 无 | `null` |
| GET | `/user/me` | 登录 | 无 | `User` |
| POST | `/user/temp-token` | 登录 | `TempTokenRequest` | `TempToken` |
| POST | `/user/passwd-change` | 登录 | J `password*` | `null` |
| GET | `/user/exists` | 管理员 | Q `username` | `{ isExist: boolean }` |
| POST | `/user/create` | 管理员 | `CreateUserRequest` | `null` |
| POST | `/user/edit` | 管理员 | `EditUserRequest` | `null` |
| POST | `/user/delete` | 管理员 | J `guid` | `null` |
| POST | `/user/unbanned` | 管理员 | J `guid*` | `null` |
| GET | `/user/list` | 管理员 | 无 | `{ list: UserListItem[] }` |

NAS 授权登录请求：

```json
{ "code": "<nas-oauth-code>", "deviceId": "my-client" }
```

临时令牌请求：

```ts
type TempTokenRequest = {
  usage: "track-stream";
  scopes: Array<"track:stream" | "track:hls">;
  resourceGUID: string;
  ttlSeconds: number;
};
```

`ttlSeconds` 有效范围为 `2..1800`；超出范围时使用 `300`。临时令牌绑定到一个歌曲 GUID，只能用于声明的 scope。

用户创建与编辑请求：

```ts
type SharedLibraryAccessRequest = {
  mode: "none" | "all" | "partial";
  guids: string[]; // mode=partial 时填写
};

type CreateUserRequest = {
  username: string; // 去除首尾空白后 1..32 个字符
  password: string; // 6..300 字节
  sharedLibraryAccess: SharedLibraryAccessRequest;
};

type EditUserRequest = {
  guid: string;
  username: string; // 去除首尾空白后 1..32 个字符
  password: string | null; // null 表示不修改
  sharedLibraryAccess: SharedLibraryAccessRequest;
};
```

## 5. 授权目录与音乐库

| 方法 | 路径 | 权限 | 参数 | `data` |
| --- | --- | --- | --- | --- |
| GET | `/app-center/authed-dir/list` | 管理员 | 无 | `{ list: AuthorizedDirectory[] }` |
| GET | `/app-center/authed-dir/sub/list` | 管理员 | Q `parent*` | `{ list: AuthorizedSubdirectory[] }` |
| POST | `/app-center/authed-dir/sub/create` | 管理员 | J `parent*`, `name*` | `null` |
| GET | `/shared-library/list` | 登录 | 无 | `{ list: SharedLibrary[] }` |
| GET | `/shared-library/detail` | 登录 | Q `guid*` | `SharedLibrary` |
| POST | `/shared-library/create` | 管理员 | `SharedLibraryWriteRequest` | `SharedLibrary` |
| POST | `/shared-library/edit` | 管理员 | J `guid*` + `SharedLibraryWriteRequest` | `null` |
| POST | `/shared-library/delete` | 管理员 | J `guid*` | `null` |
| POST | `/shared-library/scan-all` | 管理员 | 无 | `null` |
| POST | `/shared-library/scan` | 管理员 | J `guid*` | `null` |

```ts
type SharedLibraryWriteRequest = {
  path: string;
  metadataPreference: "cloud_preferred" | "local_only";
  autoDownloadLyric: boolean;
};
```

`accessStatus`：`0` 正常，`1` 目录不存在，`2` 目录无访问权限，`3` 未在应用中心授权。

### 5.1 扫描任务进度

调用音乐库扫描接口后，可通过任务接口查看扫描、在线元数据获取和歌词下载进度。

| 方法 | 路径 | 权限 | 参数 | `data` |
| --- | --- | --- | --- | --- |
| GET | `/task/list` | 登录 | 无 | `{ list: Task[] }` |
| POST | `/task/listByTaskIds` | 登录 | J `taskIds*` | `{ list: Task[] }` |
| GET | `/task/detail` | 登录 | Q `taskId*` | `Task`（包含错误明细） |
| POST | `/task/cleanDone` | 登录 | 无 | `null` |
| POST | `/task/cancelAll` | 登录 | J `delete` | `null` |
| POST | `/task/cancel` | 登录 | J `taskId*`, `delete` | `null` |
| POST | `/task/retry` | 登录 | J `taskId*` | `null` |
| POST | `/task/delete` | 登录 | J `taskId*` | `null` |

`delete=true` 表示取消后同时移除任务；`cleanDone` 清理全部已结束任务；`delete` 只能删除已结束任务。

```ts
type Task = {
  id: string;
  type: "fileScan" | "cloudScrape" | "lyricDownload" | string;
  name: string;
  total: number;
  successCount: number;
  failCount: number;
  done: boolean;
  retryable: boolean;
  canceled: boolean;
  cancelling: boolean;
  canceledCount: number;
  createdAt: number;
  doneAt?: number;
  errStats?: Array<{ code: number; msg: string; count: number }>;
  errs?: Array<{
    code: number;
    msg: string;
    name: string;
    ext1?: string;
    ext2?: string;
    ext3?: string;
  }>;
  ext: {
    libraryGUID?: string;
    [key: string]: unknown;
  };
};
```

查询某个音乐库的扫描任务时，可先调用 `/task/list`，再按 `task.ext.libraryGUID` 过滤并保存 `task.id`，之后调用 `/task/detail?taskId=<id>` 获取错误明细。

## 6. 艺术家、专辑、流派与歌曲

### 6.1 列表与详情

| 方法 | 路径 | 权限 | 参数 | `data` |
| --- | --- | --- | --- | --- |
| GET | `/artist/list` | 登录 | Q `page`, `size`, `sort` | `SortedPageList<ArtistWithCounts>` |
| GET | `/artist/list-all` | 登录 | 无 | `PageList<Artist>` |
| GET | `/artist/detail` | 登录 | Q `guid*` | `ArtistWithCounts` |
| POST | `/artist/create` | 管理员 | J `name*`, `coverId?` | `Artist` |
| GET | `/album/list` | 登录 | Q `page`, `size`, `sort` | `SortedPageList<AlbumWithArtistsAndTrackCount>` |
| GET | `/album/artist-detail/list` | 登录 | Q `artistGUID*`, `page`, `size`, `sort` | `SortedPageList<AlbumWithArtistsAndTrackCount>` |
| GET | `/album/detail` | 登录 | Q `guid*` | `AlbumWithArtistsAndTrackCount` |
| GET | `/genre/list` | 登录 | Q `page`, `size`, `sort` | `SortedPageList<GenreWithTrackCount>` |
| GET | `/genre/detail` | 登录 | Q `guid*` | `GenreWithTrackCount` |
| POST | `/genre/create` | 管理员 | J `name*` | `Genre` |
| GET | `/track/list` | 登录 | Q `page`, `size`, `sort` | `SortedPageList<TrackWithFavorite>` |
| GET | `/track/artist-detail/list` | 登录 | Q `artistGUID*`, `page`, `size`, `sort` | `SortedPageList<TrackWithFavorite>` |
| GET | `/track/album-detail/list` | 登录 | Q `albumGUID*`, `page`, `size`, `sort` | `SortedPageList<TrackWithFavorite>` |
| GET | `/track/genre-detail/list` | 登录 | Q `genreGUID*`, `page`, `size`, `sort` | `SortedPageList<TrackWithFavorite>` |
| GET | `/track/playlist-detail/list` | 登录 | Q `playlistGUID*`, `page`, `size`, `sort` | `SortedPageList<TrackWithFavoriteAndAccess>` |
| GET | `/track/metadata` | 登录 | Q `guid*` | `TrackMetadata` |
| POST | `/track/metadata` | 管理员 | `UpdateTrackMetadataRequest` | `null` |
| POST | `/track/delete` | 管理员 | F `trackGUID*`, `isPermanent` | `null` |
| POST | `/track/recover` | 管理员 | F `trackGUID*` | `null` |
| GET | `/lyric/list` | 登录 | Q `trackGUID*` | `LyricList` |

`track/delete` 和 `track/recover` 使用 `application/x-www-form-urlencoded`，例如：

```bash
curl -sS -X POST "$MUSIC_API_BASE/track/delete" \
  -H "Authorization: $MUSIC_TOKEN" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'trackGUID=<track-guid>' \
  --data-urlencode 'isPermanent=false'
```

歌曲元数据更新请求：

```ts
type UpdateTrackMetadataRequest = {
  guid: string;
  title?: string | null;
  artistGUIDs: string[];
  album: string | null;
  genreGUIDs: string[];
  year: number | null;
  discNo: number | null;
  trackNo: number | null;
  coverId: string | null;
};
```

该接口应按“完整元数据更新”调用：`artistGUIDs` 或 `genreGUIDs` 为空会清空关联；`album`、`year`、`discNo`、`trackNo`、`coverId` 为 `null` 或被省略会清空对应值；只有 `title` 为 `null` 或被省略时表示保留原值。艺术家和流派名称最长 255 个字符。

### 6.2 排序字段

| 列表 | 可用字段 | 默认值 |
| --- | --- | --- |
| 艺术家 | `name`, `trackCount`, `albumCount` | `trackCount,desc` |
| 专辑 | `newTrackAddedAt`, `releaseYear`, `name`, `artistName`, `trackCount` | `newTrackAddedAt,desc` |
| 艺术家详情中的专辑 | `newTrackAddedAt`, `releaseYear`, `name`, `trackCount` | `newTrackAddedAt,desc` |
| 全部歌曲 | `createdAt`, `title` | `createdAt,desc` |
| 艺术家详情中的歌曲 | `createdAt` | `createdAt,desc` |
| 专辑详情中的歌曲 | `trackNo`, `title` | `trackNo,asc` |
| 流派详情中的歌曲 | `createdAt`, `artistName` | `createdAt,desc` |
| 歌单详情中的歌曲 | `trackAddedAt`, `title` | `trackAddedAt,desc` |
| 流派 | `name`, `trackCount` | `trackCount,desc` |

非法排序值会回退到对应默认值；实际采用的值在响应 `data.sort` 中返回。

### 6.3 随机漫游

| 方法 | 路径 | 权限 | 参数 | `data` |
| --- | --- | --- | --- | --- |
| GET | `/track/roam-start` | 登录 | Q `deviceId*` | `RoamStart` |
| GET | `/track/roam-previous` | 登录 | Q `deviceId*`, `relativeRoamId*` | `RoamWindow` |
| GET | `/track/roam-next` | 登录 | Q `deviceId*`, `relativeRoamId*` | `RoamWindow` |

客户端应保存返回的 `roamId`，并在上一首/下一首请求中作为 `relativeRoamId` 传入。

## 7. 收藏、播放历史与歌单

| 方法 | 路径 | 权限 | 参数 | `data` |
| --- | --- | --- | --- | --- |
| POST | `/favorite-track/create` | 登录 | J `trackGUID*` | `null` |
| POST | `/favorite-track/delete` | 登录 | J `trackGUID*` | `null` |
| GET | `/favorite-track/list` | 登录 | Q `page`, `size`, `sort` | `PageList<TrackWithFavoriteAndAccess>` |
| GET | `/favorite-track/purge-track-count` | 登录 | 无 | `{ total: number }` |
| POST | `/favorite-track/purge-track` | 登录 | 无 | `null` |
| POST | `/play-history/delete` | 登录 | J `trackGUIDs*` | `null` |
| GET | `/play-history/list` | 登录 | Q `page`, `size` | `PageList<TrackWithFavorite>` |
| POST | `/playlist/create` | 登录 | J `name*`, `coverId?` | `Playlist` |
| POST | `/playlist/edit` | 登录 | J `guid*`, `name*`, `coverId?` | `null` |
| POST | `/playlist/delete` | 登录 | J `guid*` | `null` |
| GET | `/playlist/list` | 登录 | 无 | `PageList<Playlist>` |
| GET | `/playlist/detail` | 登录 | Q `guid*` | `PlaylistWithTrackCount` |
| GET | `/playlist/batch-detail` | 登录 | Q `guids`（逗号分隔） | `{ list: PlaylistWithTrackCount[] }` |
| POST | `/playlist/add-track` | 登录 | J `guid*`, `trackGUIDs` | `null` |
| POST | `/playlist/remove-track` | 登录 | J `guid*`, `trackGUIDs` | `null` |
| GET | `/playlist/purge-track-count` | 登录 | Q `guid*` | `{ total: number }` |
| POST | `/playlist/purge-track` | 登录 | J `guid*` | `null` |

收藏列表排序支持 `title`、`favoriteAt`，默认 `favoriteAt,desc`。上述 `purge-track` 接口用于清除已不可访问的歌曲记录。

歌单名称去除首尾空白后长度必须为 `1..32` 个字符。

## 8. 搜索

| 方法 | 路径 | 权限 | 参数 | `data` |
| --- | --- | --- | --- | --- |
| GET | `/search/suggest` | 登录 | Q `q*` | `SearchSuggestion` |
| GET | `/search/track` | 登录 | Q `q*` | `PageList<SearchTrack>` |
| GET | `/search/album` | 登录 | Q `q*` | `PageList<SearchAlbum>` |
| GET | `/search/artist` | 登录 | Q `q*` | `PageList<SearchArtist>` |
| GET | `/search/playlist` | 登录 | Q `q` | `PageList<SearchPlaylist>` |
| POST | `/search/index/rebuild` | 管理员 | 无 | `SearchRebuildResult` |

搜索建议每类最多返回 5 条；完整搜索每类最多返回 500 条。搜索对象在基础对象字段之外增加 `score: number`。

## 9. 播放与转码

| 方法 | 路径 | 权限 | 参数 | 响应 |
| --- | --- | --- | --- | --- |
| GET | `/track/audio-info` | 登录 | Q `guid*` | `ApiResult<AudioSpec>` |
| GET | `/track/stream` | 临时/登录 | Q `guid*`, `tempToken?` | 音频文件流 |
| POST | `/track/transcode` | 登录 | `TranscodeRequest` | `ApiResult<TranscodeResult>` |
| POST | `/track/transcode/quit` | 登录 | J `guid*` | `ApiResult<MediaTaskResult>` |
| POST | `/track/transcode/heartbeat` | 登录 | J `guid*`, `timestamp*` | `ApiResult<MediaTaskResult>` |
| GET | `/track/hls/{guid}/{filename}` | 临时/登录 | P `guid*`, `filename*`; Q `tempToken?` | HLS 内容 |

原始音频流支持 `Range`、`If-None-Match` 等标准文件请求头。响应可能为 `200`、`206`、`304` 或 `416`，并暴露 `Accept-Ranges`、`Content-Range`、`Content-Length`、`ETag`、`Last-Modified`。

```ts
type TranscodeRequest = {
  guid: string;
  output: {
    start: number;       // 秒，<=0 表示从头开始
    end?: number | null; // 秒
    codec: string;
    bitrate?: number | null;
    channel?: number | null;
  };
};

type MediaTaskResult = {
  status: string;
  errno: string;
  errmsg: string;
};

type TranscodeResult = MediaTaskResult & {
  hlsTime: number;
  url: string;
};
```

`url` 是 HLS 播放地址。持续播放时按客户端需要调用 heartbeat；停止播放时调用 quit。

## 10. 下载

| 方法 | 路径 | 权限 | 参数 | 响应 |
| --- | --- | --- | --- | --- |
| GET | `/download/track` | 登录 | Q `guid*` | 原始音频附件 |
| POST | `/download/track/transcode/prepare` | 登录 | J `trackGUID*`, `quality*` | `ApiResult<DownloadPrepare>` |
| GET | `/download/track/transcode/status` | 登录 | Q `downloadId*` | `ApiResult<DownloadStatus>` |
| GET | `/download/track/transcode/file` | 登录 | Q `downloadId*` | 转码后音频附件 |
| POST | `/download/track/transcode/delete` | 登录 | J `downloadId*` | `ApiResult<DownloadDelete>` |
| GET | `/download/track/detail` | 登录 | Q `trackGUID*` | `ApiResult<DownloadTrackDetail>` |

`quality` 可选值：`original`、`high`、`standard`。

典型转码下载流程：

1. 调用 `prepare`，保存 `data.downloadId`。
2. 轮询 `status`，直到 `data.status=ready` 且 `data.percent=100`。
3. 调用 `file` 下载文件。
4. 调用 `delete` 清理该下载任务。

状态值包括 `transcoding`、`ready`、`failed`、`expired`。

```ts
type DownloadPrepare = MediaTaskResult & { downloadId: string };
type DownloadStatus = MediaTaskResult & { downloadId: string; percent: number };
type DownloadDelete = { downloadId: string; deleted: boolean };
```

## 11. 封面与目录视图

| 方法 | 路径 | 权限 | 参数 | 响应 |
| --- | --- | --- | --- | --- |
| GET | `/static/cover` | 登录 | Q `coverId*`, `size` | 图片文件 |
| POST | `/static/cover/track` | 管理员 | M `file*` | `ApiResult<{ coverId: string }>` |
| POST | `/static/cover/playlist` | 登录 | M `file*` | `ApiResult<{ coverId: string }>` |
| GET | `/folder-view/list` | 登录 | 无 | `ApiResult<FolderViewList>` |

上传封面：

```bash
curl -sS -X POST "$MUSIC_API_BASE/static/cover/playlist" \
  -H "Authorization: $MUSIC_TOKEN" \
  -F 'file=@/absolute/path/cover.jpg'
```

获取封面：

```bash
curl -L "$MUSIC_API_BASE/static/cover?coverId=<cover-id>&size=512" \
  -H "Authorization: $MUSIC_TOKEN" \
  -o cover.jpg
```

封面响应使用长期缓存，并带有 `ETag`。`size` 用于请求对应尺寸的缩略图。

## 12. 设置与事件

| 方法 | 路径 | 权限 | 参数 | `data` |
| --- | --- | --- | --- | --- |
| GET | `/settings/user` | 管理员 | 无 | `UserSettings` |
| POST | `/settings/user` | 管理员 | `UpdateUserSettingsRequest` | `null` |
| GET | `/settings/server` | 管理员 | 无 | `{ name: string, lang: string }` |
| POST | `/settings/server` | 管理员 | J `name*`, `lang` | `null` |
| POST | `/event/report` | 登录 | `EventReportRequest` | `null` |

服务名称去除首尾空白后长度必须为 `1..32` 个字符。

```ts
type UpdateUserSettingsRequest = {
  defaultSharedLibraryAccess: {
    mode: "none" | "all" | "partial";
    guids: string[];
  };
};

type EventReportRequest = {
  events: Array<{
    eventType:
      | "track_play"
      | "lyric_preference_change"
      | "lyric_offset_change"
      | "sorting_change";
    occurredAt: number;
    payload: Record<string, unknown>;
  }>;
};
```

一次最多上报 200 个事件。各事件的 `payload`：

| `eventType` | `payload` |
| --- | --- |
| `track_play` | `{ trackGUID: string }` |
| `lyric_preference_change` | `{ trackGUID: string, lyricGUID: string }` |
| `lyric_offset_change` | `{ trackGUID: string, lyricGUID: string, offset: number }` |
| `sorting_change` | `{ id: string, sorting: string }` |

## 13. 响应对象

以下定义省略了 `ApiResult<T>` 外层包装。

### 13.1 系统与用户

```ts
type InitializationState = { initialized: boolean };

type InitializationPrepare = {
  sessionId: string;
  operator: { name: string; isAdmin: boolean };
};

type LoginResult = { userToken: string; user: User };

type User = {
  guid: string;
  name: string;
  role: "admin" | "member";
  lastAccessedAt: number | null;
  createdAt: number;
  updatedAt: number;
};

type UserListItem = User & {
  sharedLibraryAccess: {
    mode: "none" | "all" | "partial";
    sharedLibraries: Array<{ guid: string; name: string }>;
  };
  trimNasUserId: string | null;
  trimNasUsername: string | null;
  isBanned: boolean;
  bannedUntil: number | null;
};

type TempToken = { token: string; expiredAt: number };

type SystemConfig = {
  nasOAuth: { clientId: string };
  serverGUID: string;
  serverName: string;
  serverVersion: string;
  mediasrvVersion: string;
};

type SystemInfo = {
  connect: {
    id: string;
    status: string;
    entitlement: { type: string; endTime: number } | null;
  };
};
```

### 13.2 音乐对象

```ts
type Artist = {
  guid: string;
  name: string;
  coverId: string | null;
  createdAt: number;
  updatedAt: number;
};

type ArtistWithCounts = Artist & {
  trackCount: number;
  albumCount: number;
};

type Album = {
  guid: string;
  name: string;
  coverId: string | null;
  releaseDate: string | null;
  barcode: string | null;
  createdAt: number;
  updatedAt: number;
};

type AlbumWithArtistsAndTrackCount = Album & {
  artists: Artist[];
  trackCount: number;
};

type Genre = {
  guid: string;
  name: string;
  coverId: string | null;
  createdAt: number;
  updatedAt: number;
};

type GenreWithTrackCount = Genre & { trackCount: number };

type AudioSpec = {
  bitDepth: number | null;
  sampleRate: number;
  channel: number;
  bitrate: number;
  codec: string;
  container: string;
  duration: number;
  format: string;
  path: string;
  size: number;
};

type Track = {
  guid: string;
  title: string;
  coverId: string | null;
  year: number | null;
  discNo: number | null;
  trackNo: number | null;
  isrc: string | null;
  duration: number;
  isCue: boolean;
  createdAt: number;
  updatedAt: number;
  album: Album | null;
  artists: Artist[];
  genres: Genre[];
  audioSpec: AudioSpec;
};

type TrackWithFavorite = Track & { isFavorite: boolean };
type TrackWithFavoriteAndAccess = TrackWithFavorite & { accessStatus: number };

type TrackMetadata = {
  audioSpec: AudioSpec;
  track: TrackWithFavorite & { hasLyric: boolean };
};
```

歌曲 `accessStatus`：`0` 正常，`1` 音乐库目录不可访问，`2` 无音乐库权限，`3` 音频文件不存在，`4` 音频文件无权限。

### 13.3 歌单、歌词与漫游

```ts
type Playlist = {
  guid: string;
  name: string;
  coverId: string | null;
  createdAt: number;
  updatedAt: number;
};

type PlaylistWithTrackCount = Playlist & { trackCount: number };

type Lyric = {
  guid: string;
  source: 1 | 2 | 3 | 4;
  content: string;
  createdAt: number;
  updatedAt: number;
  isLRC: boolean;
  offset: number;
};

type LyricList = {
  list: Lyric[];
  preferred: string | null;
};

type RoamTrack = { roamId: string; track: Track };
type RoamStart = { current: RoamTrack; next: RoamTrack | null };
type RoamWindow = {
  previous: RoamTrack | null;
  current: RoamTrack;
  next: RoamTrack | null;
};
```

歌词 `source`：`1` 内嵌，`2` 同目录文件，`3` 在线获取，`4` 用户关联。

### 13.4 音乐库、目录和下载详情

```ts
type SharedLibrary = {
  guid: string;
  name: string;
  path: string;
  autoDownloadLyric: boolean;
  metadataPreference: "cloud_preferred" | "local_only";
  contentLastChangedAt: number;
  createdAt?: number;
  updatedAt?: number;
  accessStatus: number;
};

type AuthorizedDirectory = {
  path: string;
  storageType: number;
  cloudStorageType: number;
  permission: string;
  uname: string;
  address: string;
  comment: string;
  username: string;
};

type AuthorizedSubdirectory = { path: string; name: string };

type FolderViewList = { list: Folder[] };
type Folder = {
  name: string;
  path: string;
  folders: Folder[];
  tracks: TrackWithFavorite[];
};

type DownloadTrackDetail = {
  track: Track;
  album: Album | null;
  genres: Genre[];
  lyrics: Array<Omit<Lyric, "isLRC" | "offset">>;
};
```

### 13.5 搜索与设置

```ts
type SearchTrack = TrackWithFavorite & { score: number };
type SearchAlbum = AlbumWithArtistsAndTrackCount & { score: number };
type SearchArtist = ArtistWithCounts & { score: number };
type SearchPlaylist = PlaylistWithTrackCount & { score: number };

type SearchSuggestion = {
  track: { total: number; items: SearchTrack[] };
  album: { total: number; items: SearchAlbum[] };
  artist: { total: number; items: SearchArtist[] };
  playlist: { total: number; items: SearchPlaylist[] };
};

type SearchRebuildResult = {
  trackCount: number;
  albumCount: number;
  artistCount: number;
  playlistCount: number;
  totalCount: number;
  durationMs: number;
};

type UserSettings = {
  defaultSharedLibraryAccess: {
    mode: "none" | "all" | "partial";
    sharedLibraries: Array<{ guid: string; name: string }>;
  };
};
```

## 14. 错误码

业务错误使用统一 JSON 响应。常见错误码：

| `code` | `msg` | 含义 |
| ---: | --- | --- |
| `99999` | `INVALID TOKEN` | 令牌无效 |
| `100001` | `unknown error` | 未知错误 |
| `100002` | `invalid arguments` | 参数无效 |
| `100003` | `forbidden, admin only` | 仅管理员可用 |
| `100004` | `forbidden` | 无权访问资源 |
| `100005` | `resource not found` | 资源不存在 |
| `110001` | `app is already initialized` | 已完成初始化 |
| `110002` | `app is not initialized` | 尚未初始化 |
| `110003` | `initialization requires NAS admin` | 初始化要求 NAS 管理员 |
| `110004` | `initialization session not found or expired` | 初始化会话不存在或已过期 |
| `120001` | `unauthorized, please login again` | 未授权，需要重新登录 |
| `120002` | `account has been disabled` | 账号已禁用 |
| `120003` | `oauth user already exists` | OAuth 用户已存在 |
| `120004` | `username already exists` | 用户名已存在 |
| `120005` | `invalid username` | 用户名格式无效 |
| `120006` | `password is required` | 必须提供密码 |
| `130001` | `cue track missing start or end offset` | CUE 歌曲缺少起止位置 |
| `140001` | `search index rebuild already in progress` | 搜索索引正在重建 |
| `150001` | `shared library name already exists` | 音乐库名称已存在 |
| `150002` | `shared library path already exists` | 音乐库路径已存在 |
| `150003` | `shared library no write permission` | 音乐库路径不可写 |
| `150004` | `shared library hit max count` | 音乐库数量达到上限 |
| `150005` | `shared library sub path already exists` | 音乐库子路径已存在 |
| `160001` | `playlist name already exists` | 歌单名称已存在 |
| `160002` | `playlist hit max count` | 歌单数量达到上限 |
| `200010001` | `task not found` | 任务不存在 |
| `200010002` | `task is running` | 任务仍在运行，不能删除 |
| `200010003` | `invalid task create param` | 任务参数无效 |
| `200010004` | `task cannot be retried` | 任务不支持重试 |
| `200020005` | `task item canceled` | 任务项已取消 |

调用端推荐统一按以下顺序处理：先判断 HTTP 状态；对于 JSON 响应再判断 `code === 0`；文件接口则根据 HTTP 状态和 `Content-Type` 处理正文。
