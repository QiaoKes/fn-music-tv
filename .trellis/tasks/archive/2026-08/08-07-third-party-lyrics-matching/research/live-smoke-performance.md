# Live source smoke and latency sample

Date: 2026-08-07, Asia/Shanghai

One anonymous HTTPS search for `周杰伦 晴天` was sent to each active source from the development machine. This is a connectivity sample, not a statistical benchmark:

| Source | HTTP | Search elapsed |
| --- | ---: | ---: |
| QQ Music | 200 | 476 ms |
| Netease | 200 | 270 ms |
| Kugou | 200 | 113 ms |

LRCLIB returned HTTP 200 in a preliminary sample but took 2,217 ms, so it was removed from the active/default provider set for latency. The coordinator now searches only QQ Music, Kugou and Netease. Deterministic tests still verify cancellation of a slow source, concurrent search/fetch, a 3,000 ms total production deadline, and reuse of a persisted positive match without another provider call.

No NAS credentials, cookies, access codes, tokens, full response bodies, or lyric bodies were recorded.
