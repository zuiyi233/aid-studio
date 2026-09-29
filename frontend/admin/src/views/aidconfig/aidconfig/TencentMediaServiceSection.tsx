import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, Button, Form, Space, Spin, Switch, message } from 'antd';
import { ReloadOutlined, SaveOutlined } from '@ant-design/icons';
import PageCard from '@/components/PageCard';
import { getTencentMediaService, saveTencentMediaService } from '@/api/aidconfig/tencentMedia';

type Service = 'portrait' | 'voice' | 'subtitle';
const DETAILS: Record<Service, { title: string; description: string; modes: string }> = {
  portrait: { title: '视频人像分割', description: '数据万象从 COS 桶读取视频，并将处理结果写回 COS。', modes: 'Mask 遮罩、Foreground 人像前景、Combination 人像背景合成' },
  voice: { title: '人声／背景音分离', description: '数据万象从 COS 桶读取音视频，结果按模型能力保存为音频。', modes: '人声、背景音、人声与背景音' },
  subtitle: { title: '去字幕（MPS）', description: '腾讯云媒体处理支持受控 URL 或 COS 输入，结果写入处理 COS。', modes: '智能去字幕、框选去字幕' }
};

export default function TencentMediaServiceSection({ service }: { service: Service }) {
  const [form] = Form.useForm<{ enabled: boolean }>();
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const pending = useRef(false);
  const detail = DETAILS[service];

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const response: any = await getTencentMediaService(service);
      form.setFieldsValue({ enabled: response.data?.enabled !== 'false' });
    } catch { message.error(`${detail.title}配置读取失败`); }
    finally { setLoading(false); }
  }, [detail.title, form, service]);

  useEffect(() => { void load(); }, [load]);

  const save = async () => {
    if (pending.current) return;
    pending.current = true;
    setSaving(true);
    try {
      const { enabled } = await form.validateFields();
      await saveTencentMediaService(service, enabled);
      message.success('服务开关已保存');
      await load();
    } catch (error: any) {
      if (!error?.errorFields) message.error('保存失败');
    } finally {
      pending.current = false;
      setSaving(false);
    }
  };

  if (loading) return <Spin />;
  return <PageCard title={detail.title}>
    <Alert type="info" showIcon message={detail.description} description={`可用模式：${detail.modes}。具体模型能力和价格仍在模型管理中维护。`} style={{ marginBottom: 16 }} />
    <Form form={form} layout="vertical">
      <Form.Item name="enabled" label={`启用${detail.title}`} valuePropName="checked"><Switch /></Form.Item>
      <Space>
        <Button type="primary" icon={<SaveOutlined />} loading={saving} disabled={saving} onClick={() => void save()}>保存</Button>
        <Button icon={<ReloadOutlined />} disabled={saving} onClick={() => void load()}>刷新</Button>
      </Space>
    </Form>
    <p style={{ marginTop: 16 }}>连接检查请先在“COS 配置”完成；真实处理请使用对应模型的测试入口。真实测试会产生腾讯云费用。</p>
  </PageCard>;
}
