import React from 'react';
import { Checkbox, Collapse, Divider, Select, Space, Switch } from 'antd';
import CapabilityEditor from './CapabilityEditor';
import StructuredValueEditor, { type ConfigValue } from './StructuredValueEditor';
import { buildCapabilityJsonObject, parseCapabilityJsonToModel } from './helpers';
import type { Model } from './types';
import type { ModelCapabilityDefinition, ModelProtocolBinding } from './modelDefinition';

/** 保留已有中文素材约束控件，配置归属于当前能力的调用渠道。 */
export default function ModelRouteCapabilityEditor({ definition, route, modelType, onChange }: {
  definition: ModelCapabilityDefinition;
  route: ModelProtocolBinding;
  modelType: string;
  onChange: (patch: Partial<ModelProtocolBinding>) => void;
}) {
  const form = { ...definition.presentation, ...route.presentation, modelType,
    generateMode: definition.generateMode, protocol: route.protocol } as Model;
  const cap = parseCapabilityJsonToModel(JSON.stringify(route.capability || {}));
  // 常用控件只修改明确编辑的字段，避免对整份能力 JSON 做无关的默认值归一化。
  const patchCapability = (patch: Record<string, ConfigValue>) => { if (!cap.parseError) onChange({ capability: { ...route.capability, ...patch } }); };
  const targetResolutions = Array.isArray(route.capability?.targetResolutionOptions)
    ? route.capability.targetResolutionOptions.filter((value): value is string => typeof value === 'string') : null;
  const selectedSizes = targetResolutions || cap.sizeOptions;
  const sizes = targetResolutions || Array.from(new Set(['480P', '720P', '1080P', '2K', '4K', ...cap.sizeOptions]));
  const customResolution = !targetResolutions && cap.allowCustomWH === true && cap.sizeOptions.length === 0;
  const durations = Array.from(new Set([5, 10, 15, ...cap.durationOptions].map(Number)))
    .filter(Number.isFinite).sort((a, b) => a - b);
  return <>
    {(modelType === 'video' || modelType === 'image') && <section className="model-capability-quick" aria-label="常用能力配置">
      <h3>常用能力配置</h3>
      <div className="model-capability-quick__field">
        <strong>分辨率 / 规格</strong>
        {customResolution ? <small>按目标宽高设置输出尺寸，最大 {cap.maxOutputPixels
          ? `${Math.floor(cap.maxOutputPixels / 1000000)} MP` : '像素上限以模型能力为准'}；下方可调整像素限制。</small>
          : <Checkbox.Group disabled={cap.parseError} aria-label="分辨率 / 规格" options={sizes} value={selectedSizes}
            onChange={(values) => patchCapability(targetResolutions
              ? { targetResolutionOptions: values as string[] }
              : { sizeOptions: [
                  ...cap.sizeOptions.filter((value) => values.includes(value)),
                  ...values.filter((value) => !cap.sizeOptions.includes(value))
                ] })} />}
        {!customResolution && <small>按模型实际支持范围勾选；自定义尺寸和上游映射在下方完整配置中维护。</small>}
      </div>
      {route.capability?.supportsAspectRatio === true && <div className="model-capability-quick__field">
        <strong>画面比例</strong>
        <Checkbox.Group disabled={cap.parseError} aria-label="画面比例" options={Array.from(new Set(['16:9', '9:16', '1:1', '4:3', '3:4', ...cap.aspectRatioOptions]))}
          value={cap.aspectRatioOptions} onChange={(values) => patchCapability({ aspectRatioOptions: [
            ...cap.aspectRatioOptions.filter((value) => values.includes(value)),
            ...values.filter((value) => !cap.aspectRatioOptions.includes(value))
          ] })} />
      </div>}
      {modelType === 'video' && <>
        {route.capability?.supportsDuration === true && <div className="model-capability-quick__field">
          <strong>视频时长（秒）</strong>
          <Checkbox.Group disabled={cap.parseError} aria-label="视频时长（秒）" options={durations}
            value={cap.durationOptions} onChange={(values) => patchCapability({
              durationOptions: Array.from(new Set(values.map(Number))).sort((a, b) => a - b)
            })} />
        </div>}
        <Space wrap size={[24, 12]}>
          <label>音画同步 / 生成声音 <Switch disabled={cap.parseError} aria-label="音画同步 / 生成声音" checkedChildren="支持" unCheckedChildren="不支持"
            checked={cap.supportsAudio === true} onChange={(enabled) => patchCapability(enabled ? { supportsAudio: true }
              : { supportsAudio: false, defaultAudio: false, supportsBgm: false, supportsVoiceControl: false, audioTypes: [] })} /></label>
          <label>默认生成声音 <Select aria-label="默认生成声音" allowClear style={{ width: 150 }} disabled={cap.parseError || !cap.supportsAudio}
            value={cap.defaultAudio ?? undefined} placeholder="未配置"
            options={[{ value: true, label: '默认开启' }, { value: false, label: '默认关闭' }]}
            onChange={(value) => patchCapability({ defaultAudio: value ?? null })} /></label>
          <label>参考音频文件 <Switch disabled={cap.parseError} aria-label="支持参考音频文件" checkedChildren="支持" unCheckedChildren="不支持"
            checked={cap.supportsReferenceAudio === true} onChange={(enabled) => patchCapability({ supportsReferenceAudio: enabled })} /></label>
          <label>参考音色 ID <Switch disabled={cap.parseError} aria-label="支持参考音色 ID" checkedChildren="支持" unCheckedChildren="不支持"
            checked={cap.supportsVoiceId === true} onChange={(enabled) => patchCapability({ supportsVoiceId: enabled })} /></label>
          <label>视频输入 <Switch disabled={cap.parseError} aria-label="支持视频输入" checkedChildren="支持" unCheckedChildren="不支持"
            checked={cap.supportsVideoInput === true} onChange={(enabled) => patchCapability({ supportsVideoInput: enabled })} /></label>
        </Space>
      </>}
    </section>}
    <Divider orientation="left">完整能力与素材限制</Divider>
    <CapabilityEditor modelType={modelType} form={form} cap={cap}
      onCapChange={(next) => onChange({ capability: { ...route.capability, ...buildCapabilityJsonObject(form, next) } })}
      onFormChange={(patch) => onChange({ presentation: { ...route.presentation, ...patch } as ModelProtocolBinding['presentation'] })} />
    <Collapse items={[{ key: 'protocol', label: '协议附加选项', children:
      <StructuredValueEditor fixedType="object" value={route.capability || {}}
        onChange={(next) => onChange({ capability: next as ModelProtocolBinding['capability'] })} /> }]} />
  </>;
}
