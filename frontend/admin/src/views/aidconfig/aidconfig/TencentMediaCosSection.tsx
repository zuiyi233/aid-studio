import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, Button, Form, Input, Modal, Space, Spin, Tag, message } from 'antd';
import { ExperimentOutlined, ImportOutlined, ReloadOutlined, SaveOutlined } from '@ant-design/icons';
import PageCard from '@/components/PageCard';
import { checkTencentMediaCos, getTencentMediaCos, importSiteCos, saveTencentMediaCos, type TencentMediaCos } from '@/api/aidconfig/tencentMedia';

export default function TencentMediaCosSection() {
  const [form] = Form.useForm<TencentMediaCos>();
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<'save' | 'import' | 'check' | null>(null);
  const [config, setConfig] = useState<TencentMediaCos | null>(null);
  const [checkResult, setCheckResult] = useState<'success' | 'error' | null>(null);
  const [importPreview, setImportPreview] = useState(false);
  const pending = useRef(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const response: any = await getTencentMediaCos();
      const next = response.data as TencentMediaCos;
      form.setFieldsValue(next);
      setConfig(next);
      setImportPreview(false);
    } catch {
      message.error('腾讯云媒体 COS 配置读取失败');
    } finally { setLoading(false); }
  }, [form]);

  useEffect(() => { void load(); }, [load]);

  const run = async (kind: 'save' | 'import' | 'check', action: () => Promise<unknown>) => {
    if (pending.current) return;
    pending.current = true;
    setBusy(kind);
    try {
      const response: any = await action();
      if (kind === 'import') {
        form.setFieldsValue({ region: response.data.region, bucketName: response.data.bucketName,
          secretId: '', secretKey: '' });
        setImportPreview(true);
        setCheckResult(null);
        message.success('已填入网站 COS 的脱敏预览，保存后才会生效');
      } else if (kind === 'save') {
        setImportPreview(false);
        setCheckResult(null);
        await load();
        message.success('处理 COS 配置已保存');
      } else {
        setCheckResult('success');
        await load();
        message.success('COS 对象写入、读取和清理检查通过');
      }
    } catch {
      if (kind === 'check') { setCheckResult('error'); await load(); }
      // 请求层已展示服务端返回的具体失败原因，避免再叠加泛化提示。
    } finally {
      pending.current = false;
      setBusy(null);
    }
  };

  const save = async (confirmLegacyConflict = false) => {
    try {
      const values = await form.validateFields();
      await run('save', () => saveTencentMediaCos({ ...values, confirmLegacyConflict,
        importFromSite: importPreview }));
    } catch (error: any) {
      if (!error?.errorFields) message.error('配置校验失败');
    }
  };

  const confirmSave = () => {
    if (config?.legacyConflict === 'true') {
      Modal.confirm({ title: '确认统一处理 COS',
        content: '旧图像识别和视频处理目前使用不同的 COS 桶。保存后两类服务将使用本页指定的处理桶；请先核对桶权限与在途任务。',
        okText: '确认统一', cancelText: '取消', onOk: () => save(true) });
      return;
    }
    void save();
  };

  const confirmImport = () => Modal.confirm({
    title: '使用网站当前 COS 配置',
    content: config?.legacyConflict === 'true'
      ? '旧图像识别和视频处理使用不同的 COS 桶。导入后两类服务统一使用网站当前的 COS 配置，原有任务需先完成。请确认目标桶已开通所需服务。'
      : '将网站当前的地域、桶和凭证复制到腾讯云媒体服务配置。保存后两处独立维护。',
    okText: '确认填入',
    cancelText: '取消',
    onOk: () => run('import', importSiteCos)
  });

  if (loading && !config) return <Spin />;

  return <Space direction="vertical" size={16} style={{ width: '100%' }}>
    <PageCard title="处理专用 COS">
      <Alert type="info" showIcon style={{ marginBottom: 16 }} message="此处的 COS 独立于网站文件存储。数据万象任务在该桶中读取输入并写入结果；网站使用其他存储时，由服务端按需要转存。" />
      <Space wrap style={{ marginBottom: 16 }}>
        <Tag color={config?.configured === 'true' ? 'green' : 'orange'}>{config?.configured === 'true' ? '配置完整' : '待配置'}</Tag>
        {config?.legacy === 'true' && <Tag color="gold">当前沿用旧配置，保存或导入后转为独立配置</Tag>}
        {config?.legacyConflict === 'true' && <Tag color="red">旧图像识别与视频处理的桶不同，需确认迁移目标</Tag>}
        {checkResult && <Tag color={checkResult === 'success' ? 'green' : 'red'}>{checkResult === 'success' ? '本次连接检查通过' : '本次连接检查失败'}</Tag>}
        {config?.lastTestAt && <Tag color={config.lastTestStatus === 'SUCCESS' ? 'green' : 'red'}>
          上次 COS 检查：{config.lastTestStatus === 'SUCCESS' ? '通过' : '失败'} · {config.lastTestAt}
        </Tag>}
      </Space>
      {importPreview && <Alert type="warning" showIcon style={{ marginBottom: 16 }}
        message="网站 COS 配置尚未生效"
        description="页面仅显示地域与桶。点击“保存配置”后，服务端复制网站当前凭证并验证读写；取消可点“刷新”。" />}
      {config?.lastTestStatus === 'FAILED' && config.lastTestError && <Alert type="error" showIcon
        style={{ marginBottom: 16 }} message={config.lastTestError} />}
      <Form form={form} layout="vertical" requiredMark="optional">
        <Form.Item name="region" label="COS 地域" rules={[{ required: true, message: '请选择或填写 COS 地域' }, { pattern: /^[a-z0-9-]{3,64}$/, message: '地域格式错误' }]}>
          <Input placeholder="ap-shanghai" autoComplete="off" disabled={importPreview} />
        </Form.Item>
        <Form.Item name="bucketName" label="COS 桶名称" rules={[{ required: true, message: '请输入完整桶名称' }, { pattern: /^[A-Za-z0-9-]+-[0-9]+$/, message: '桶名称须包含 AppId' }]}>
          <Input placeholder="example-1250000000" autoComplete="off" disabled={importPreview} />
        </Form.Item>
        <Form.Item name="secretId" label="SecretId" extra="留空或保留掩码则继续使用已保存的密钥">
          <Input.Password autoComplete="new-password" visibilityToggle={false} disabled={importPreview} />
        </Form.Item>
        <Form.Item name="secretKey" label="SecretKey" extra="密钥在服务端保存，页面只显示掩码">
          <Input.Password autoComplete="new-password" visibilityToggle={false} disabled={importPreview} />
        </Form.Item>
        <Form.Item name="stagingPrefix" label="输入暂存目录" rules={[{ required: true, message: '请输入目录' }, { pattern: /^[A-Za-z0-9_/-]+\/$/, message: '使用桶内相对目录，并以 / 结尾' }]}>
          <Input placeholder="aid-ci/staging/" />
        </Form.Item>
        <Form.Item name="outputPrefix" label="处理结果目录" rules={[{ required: true, message: '请输入目录' }, { pattern: /^[A-Za-z0-9_/-]+\/$/, message: '使用桶内相对目录，并以 / 结尾' }]}>
          <Input placeholder="aid-ci/output/" />
        </Form.Item>
        <Space wrap>
          <Button type="primary" icon={<SaveOutlined />} loading={busy === 'save'} disabled={!!busy} onClick={confirmSave}>保存配置</Button>
          <Button icon={<ImportOutlined />} loading={busy === 'import'} disabled={!!busy || config?.siteCosAvailable !== 'true'} onClick={confirmImport}>一键使用网站 COS 配置</Button>
          <Button icon={<ExperimentOutlined />} loading={busy === 'check'} disabled={!!busy || config?.configured !== 'true'} onClick={() => void run('check', checkTencentMediaCos)}>检查 COS 读写</Button>
          <Button icon={<ReloadOutlined />} disabled={!!busy} onClick={() => void load()}>刷新</Button>
        </Space>
      </Form>
      {config?.siteCosAvailable !== 'true' && <p style={{ marginTop: 12 }}>网站当前没有可导入的 COS 配置，可直接填写此处配置。</p>}
      <p style={{ marginTop: 12 }}>连接检查会创建并删除一个小型测试对象；通过仅代表 COS 读写正常，各处理服务仍需分别验证。</p>
    </PageCard>
  </Space>;
}
