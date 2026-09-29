# 腾讯云数据万象异步媒体任务接入

本接入把腾讯云数据万象（CI）的“视频人像分割”和“人声分离”接入统一异步媒体任务。模型目录默认停用，不预置密钥；SQL 以官方公开价初始化可配置的按秒 SKU，管理员完成 COS、数据万象服务、CAM 权限和售价复核后才能启用模型。

## C 端模型池

| 业务池 | 模型 | 默认能力 | 同组可选能力 |
|---|---|---|---|
| `video_portrait_segmentation` | `tencent-ci-video-portrait-segmentation` | `portrait_mask` | `portrait_mask`、`portrait_foreground`、`portrait_combination` |
| `audio_voice_separation` | `tencent-ci-voice-separation` | `voice_only` | `voice_only`、`background_only`、`voice_background` |

两个业务池通过原 `POST /api/user/model/listByFunc` 查询。每个模型只出现一次，其全部模式放在 `availableCapabilities[]`；客户端提交与报价必须传用户实际选择的 `capabilityCode`，不能只读取默认能力。业务池本身启用，但初始模型为停用状态，因此管理员启用模型前不会出现在 C 端可用列表。

## 能力范围

| 模型代码 | 能力代码 | 上游 Tag / Mode | 输入 | 有序结果 |
|---|---|---|---|---|
| `tencent-ci-video-portrait-segmentation` | `portrait_mask` | `SegmentVideoBody` / `Mask` | 1 个已授权视频记录 | `portrait.mp4` |
| 同上 | `portrait_foreground` | `SegmentVideoBody` / `Foreground` | 1 个已授权视频记录 | `portrait.mp4` |
| 同上 | `portrait_combination` | `SegmentVideoBody` / `Combination` | 1 个已授权视频记录和 1 张背景图 | `portrait.mp4` |
| `tencent-ci-voice-separation` | `voice_only` | `VoiceSeparate` / `IsAudio` | 1 个视频或 1 个音频 | 人声 |
| 同上 | `background_only` | `VoiceSeparate` / `IsBackground` | 1 个视频或 1 个音频 | 背景声 |
| 同上 | `voice_background` | `VoiceSeparate` / `AudioAndBackground` | 1 个视频或 1 个音频 | 人声、背景声 |

双音频结果固定按“人声、背景声”排序。人声分离查询确认全部 COS 对象后，先把每一项流式转存到当前系统对象存储；全部成功后才提交成功终态，并在同一终态事务中把有序原始地址、永久地址、MIME 和文件大小写入 `aid_media_result`。任何一项转存失败都会清理本轮已经转存的不完整结果并保持任务处理中，不会提前结算。只读取任务主 `resultUrl` 的旧调用方只能看到第一项。

人像分割支持官方字段 `SegmentType`（`HumanSeg`、`GreenScreenSeg`、`SolidColorSeg`）、`BinaryThreshold`、前景背景 RGB、纯色去除 RGB、`JobLevel` 和 `UserData`。RGB 与阈值范围为 0 至 255，任务级别为 0、1、2，`UserData` 限 1024 个可打印 ASCII 字符。背景 RGB 只允许前景模式使用；去除 RGB 只允许 `SolidColorSeg` 使用。

人声分离使用内联 `VoiceSeparate` 配置，支持 `aac`、`mp3`、`flac`、`amr`，采样率、8 至 1000 Kbps 码率和声道数按官方编码组合校验。源文件时长必须严格小于 45 分钟；任务信封保存服务端核验的输入时长。请求不会静默丢弃未知字段、素材或不兼容参数。

## 价格与结算口径

视频人像分割官方公开价为 1.2 元/分钟，即 SQL 初始 `pricePerSecond=0.02`；人声分离官方公开价为 0.08 元/分钟，即初始 `pricePerSecond=0.001333333333333333`。这两个值是可由管理员调整的人民币售价参考，模型仍默认停用，不会因初始化价格自动开放。

腾讯官方计费依据都是**输出时长**：人像分割按输出视频时长，人声分离按输出文件时长。当前创建与查询响应没有提供可直接信任的输出时长，因此系统在建任务和冻结余额前探测源媒体，按毫秒向上取整得到可信输入秒数，并以它作为 `PER_SECOND` 预冻结代理。客户端传入的 `billingDurationSeconds` 会被删除，不能改变价格；视频时长只采信服务端解析的已授权视频记录。纯音频报价在媒体未探测时标记为估算，正式提交前由服务端重新探测音频并以可信时长预冻结。

当前成功结果只上报 `actualInputVideoSeconds` 供审计，不把输入时长伪装成 `actualDuration`。没有权威输出时长时，统一结算层保留预冻结金额；这属于输入时长代理结算，不是按输出时长的精确对账。以后若腾讯任务响应增加权威输出时长，或系统对最终产物完成可信探测，可再写入 `actualDuration` 做只退不补结算。

## COS 输入与凭证

CI 创建任务的 `Input.Object` 必须是当前 COS 存储桶内的对象。本接入不把公网 URL 伪装成 CI 可直接读取的输入：每个来源先下载到有界本地临时文件，再上传到配置存储桶的 `aid-ci/staging/<token>/`，最后只向 `/jobs` 提交对象键。`Combination` 背景图也先暂存到同一存储桶。

公网下载仅允许 HTTPS、443 或默认端口，禁止用户信息、片段、重定向、压缩响应和私网、回环、链路本地、多播、保留、文档及危险过渡地址。DNS 解析结果在请求期间固定，响应流按字节上限读取。默认暂存上限为 2 GiB，可用 `aid.media.tencent-ci.max-stage-bytes` 调整到 1 MiB 至 20 GiB；这是本系统的运维保护值，不是腾讯云产品限额。

凭证复用图像识别配置的来源规则：

- `credentialSource=COS_STORAGE`：读取 `oss` 分类的 `cosRegion`、`cosBucketName`、`cosSecretId`、`cosSecretKey`。
- `credentialSource=DEDICATED`：读取 `image_object_detection` 分类的 `region`、`bucketName`、`secretId`、`secretKey`。

图像识别开关关闭时仍可复用已配置的 COS 存储凭证。代码不会读取 `aid_ai_provider.api_key` 作为腾讯云 SecretId，也不会把真实凭证写入模型 SQL。

## 异步状态和清理

提交返回的腾讯任务 ID、能力、暂存 token 和输出编码会写入紧凑任务信封。查询按任务类型调用对应 COS SDK，校验任务 ID 和 Tag，并映射以下状态：

- `Submitted`、`Running`、`Pause`：继续处理。
- `Success`：确认所有预期 COS 产物存在；人声分离还必须完成全部音频转存，随后才成功并清理暂存输入。
- `Failed`、`Cancel`：确认失败并清理暂存输入。
- 未知状态、查询异常或成功但产物尚不可见：保持处理中，不提前结算或退款。

提交开始后的 5xx、客户端传输异常或无法确认任务 ID，统一按“提交结果未知”处理，保留暂存对象和原任务的冻结状态供人工核对。腾讯创建任务接口没有接入可用于恢复的客户端幂等键，因此不能自动重提。建议为 `aid-ci/staging/` 配置覆盖异常任务保留期的 COS 生命周期规则。

输入和输出使用的本地临时文件都在 `finally` 路径删除。输出转存的运维字节上限与输入暂存共用 `aid.media.tencent-ci.max-stage-bytes`；超过该值会保持任务处理中并记录转存错误，不会把未持久化结果标为成功。

## 当前边界

- 腾讯文档提供任务回调，但当前公共实现只轮询，没有配置 `CallBack`；供应商和模型的 `supports_callback` 保持关闭。
- `VoiceSeparate.TemplateId` 与内联参数同时存在时模板优先。本实现固定使用可审计的内联参数，不接受模板 ID。
- 官方 `MusicMode` 及 `BassObject`、`DrumObject` 不在本次人声/背景声范围内，明确拒绝而不忽略。
- 公开文档没有给出视频人像分割专属的时长或文件大小上限，本接入不虚构该限制；仍受可配置暂存字节保护值约束。
- 官方价格已经写入可配置的 `PER_SECOND` SKU；模型仍默认停用。正式启用前应按账号地域、产品优惠和站内倍率复核最终售价。
- 未使用真实腾讯账号、存储桶和媒体做联调。正式启用前需要验证存储桶已绑定数据万象、相关服务已开通。官方页面列出的人声分离创建权限为 `ci:CreateMediaJobs`，查询权限为 `ci:DescribeMediaJob`；视频人像分割创建页当前列出 `ci:CreateBodyJointsDetectJob`。使用服务角色时还需 `cam:PassRole`。权限名按腾讯页面原样记录，启用前应以目标账号的 CAM 授权诊断再次核对。

## 官方依据

- [视频人像分割任务](https://cloud.tencent.com/document/product/460/83973)
- [创建人声分离任务](https://cloud.tencent.com/document/product/460/84794)
- [人声分离参数与编码限制](https://cloud.tencent.com/document/product/460/84500)
- [媒体处理支持格式与时长说明](https://cloud.tencent.com/document/product/460/36620)
- [查询任务](https://cloud.tencent.com/document/api/460/84765)
- [视频人像分割回调](https://cloud.tencent.com/document/product/460/84954)
- [人声分离回调](https://cloud.tencent.com/document/product/460/84955)
- [视频人像分割计费](https://cloud.tencent.com/document/product/436/58964)
- [音频智能处理计费（含人声分离）](https://cloud.tencent.com/document/product/436/84601)
