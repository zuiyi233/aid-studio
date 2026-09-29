import { request } from '@/utils/request';

export type TencentMediaCos = {
  region: string;
  bucketName: string;
  secretId: string;
  secretKey: string;
  stagingPrefix: string;
  outputPrefix: string;
  configured: string;
  legacy: string;
  siteCosAvailable: string;
  legacyConflict?: string;
  confirmLegacyConflict?: boolean;
  importFromSite?: boolean;
  lastTestAt?: string;
  lastTestStatus?: string;
  lastTestError?: string;
};

export const getTencentMediaCos = () => request({ url: '/aidconfig/tencent-media/cos', method: 'get' });
export const saveTencentMediaCos = (data: Partial<TencentMediaCos>) =>
  request({ url: '/aidconfig/tencent-media/cos', method: 'post', data, headers: { repeatSubmit: false } });
export const importSiteCos = () =>
  request({ url: '/aidconfig/tencent-media/cos/import-site', method: 'post',
    headers: { repeatSubmit: false } });
export const checkTencentMediaCos = () =>
  request({ url: '/aidconfig/tencent-media/cos/check', method: 'post', headers: { repeatSubmit: false }, timeout: 30000 });
export const getTencentMediaService = (service: 'portrait' | 'voice' | 'subtitle') =>
  request({ url: `/aidconfig/tencent-media/service/${service}`, method: 'get' });
export const saveTencentMediaService = (service: 'portrait' | 'voice' | 'subtitle', enabled: boolean) =>
  request({ url: `/aidconfig/tencent-media/service/${service}`, method: 'post', data: { enabled }, headers: { repeatSubmit: false } });
