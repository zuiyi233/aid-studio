import type { ModelCapabilityDefinition, ModelProtocolBinding } from './modelDefinition';
import type { Model } from './types';
import { inferMeterType } from './constants';

type JsonRecord = Record<string, unknown>;

export interface RouteBillingSummary {
  label: string;
  mode: 'FIXED' | 'SKU';
  meterType: string;
  skuCount: number;
  enabledSkuCount: number;
  pricedSkuCount: number;
  priceLabels: string[];
  hasIssue: boolean;
}

export interface BillingOverviewData {
  routes: RouteBillingSummary[];
  skuRouteCount: number;
  fixedRouteCount: number;
  skuCount: number;
  enabledSkuCount: number;
  issueCount: number;
}

const isRecord = (value: unknown): value is JsonRecord => Boolean(value) && typeof value === 'object' && !Array.isArray(value);

const finiteNumber = (value: unknown): number | null => {
  if (value == null || value === '') return null;
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed >= 0 ? parsed : null;
};

const formatAmount = (value: number): string => value.toLocaleString('zh-CN', {
  maximumFractionDigits: 8,
  useGrouping: false
});

const price = (value: unknown, unit: string): string | null => {
  const amount = finiteNumber(value);
  return amount == null ? null : `¥${formatAmount(amount)}/${unit}`;
};

/** The model-level price remains authoritative for routes without their own override. */
export function withEffectiveModelBilling(model: Partial<Model>): ModelCapabilityDefinition[] {
  let rootRule: JsonRecord = {};
  try {
    const parsed = model.billingRuleJson ? JSON.parse(model.billingRuleJson) : {};
    if (isRecord(parsed)) rootRule = parsed;
  } catch {
    // A malformed model rule must remain visibly incomplete.
  }
  const rootSkus = Array.isArray(rootRule.skus) ? rootRule.skus : [];
  return (model.capabilities || []).map((definition) => ({
    ...definition,
    bindings: definition.bindings.map((route) => {
      const mode = route.billingMode || model.billingMode;
      const routeSkus = isRecord(route.billingRule) && Array.isArray(route.billingRule.skus)
        ? route.billingRule.skus : [];
      if (mode === 'SKU' && routeSkus.length === 0 && rootSkus.length > 0 && model.billingMode === 'SKU') {
        return { ...route, billingMode: 'SKU' as const,
          billingRule: rootRule as ModelProtocolBinding['billingRule'] };
      }
      if (!route.billingMode && model.billingMode === 'FIXED' && route.costCredits == null) {
        return { ...route, billingMode: 'FIXED' as const,
          costCredits: model.costCredits == null ? undefined : model.costCredits };
      }
      return route;
    })
  }));
}

/** 与服务端 ModelBillingRuleValidator 的主价格完整性规则保持一致。 */
export function isSkuMainPriceConfigured(rawSku: unknown, fallbackMeterType: string): boolean {
  const sku = isRecord(rawSku) ? rawSku : {};
  const explicitMeterType = typeof sku.meterType === 'string' && sku.meterType.trim() !== '';
  const meterType = String(sku.meterType || fallbackMeterType || '').toUpperCase();
  if (meterType === 'TOKEN') {
    return finiteNumber(sku.inputPricePerMillion) != null && finiteNumber(sku.outputPricePerMillion) != null;
  }
  if (meterType === 'PER_IMAGE') {
    const unit = sku.outputPixelsPerUnit;
    return finiteNumber(sku.price) != null && (unit == null
      || (Number.isSafeInteger(Number(unit)) && Number(unit) > 0));
  }
  if (meterType === 'SKU_PACKAGE') return finiteNumber(sku.price) != null;
  if (meterType === 'PER_CREDIT') return finiteNumber(sku.price) != null;
  if (meterType === 'PER_SECOND') {
    return finiteNumber(sku.pricePerSecond) != null || (!explicitMeterType && Number(sku.price) > 0);
  }
  if (meterType === 'PER_CHAR') {
    return finiteNumber(sku.pricePerChar) != null || (!explicitMeterType && Number(sku.price) > 0);
  }
  return false;
}

/** 把一条 SKU 的实际计费字段转换为运营可读摘要。 */
export function skuPriceLabel(rawSku: unknown, fallbackMeterType: string): string {
  const sku = isRecord(rawSku) ? rawSku : {};
  const meterType = String(sku.meterType || fallbackMeterType || '').toUpperCase();
  if (meterType === 'TOKEN') {
    const input = price(sku.inputPricePerMillion, '百万输入Token');
    const output = price(sku.outputPricePerMillion, '百万输出Token');
    return [input, output].filter(Boolean).join(' · ') || '价格未配置';
  }
  if (meterType === 'PER_SECOND') {
    return price(sku.pricePerSecond, '秒') || price(sku.price, '次（兼容价）') || '价格未配置';
  }
  if (meterType === 'PER_CREDIT') {
    return price(sku.price, '供应商积分') || '价格未配置';
  }
  if (meterType === 'PER_CHAR') {
    const usage = price(sku.pricePerChar, '字符') || price(sku.price, '次（兼容价）');
    const surcharge = price(sku.fixedSurcharge, '次附加');
    return [usage, surcharge].filter(Boolean).join(' + ') || '价格未配置';
  }
  if (meterType === 'PER_IMAGE') {
    const pixelsPerUnit = finiteNumber(sku.outputPixelsPerUnit);
    if (pixelsPerUnit && Number.isInteger(pixelsPerUnit)) {
      return price(sku.price, `${formatAmount(pixelsPerUnit / 1_000_000)} MP 输出单位（向上取整）`) || '价格未配置';
    }
    return price(sku.price, '张') || '价格未配置';
  }
  if (meterType === 'SKU_PACKAGE') {
    const packagePrice = price(sku.price, '次');
    const surcharge = price(sku.fixedSurcharge, '次附加');
    return [packagePrice, surcharge].filter(Boolean).join(' + ') || '价格未配置';
  }
  return price(sku.price, '次') || '价格未配置';
}

export function summarizeRouteBilling(
  route: Partial<ModelProtocolBinding>,
  label = '默认调用协议',
  fallbackMeterType = ''
): RouteBillingSummary {
  const mode = route.billingMode === 'SKU' ? 'SKU' : 'FIXED';
  const rule = isRecord(route.billingRule) ? route.billingRule : {};
  const meterType = String(rule.meterType || fallbackMeterType || '').toUpperCase();
  if (mode === 'FIXED') {
    const fixedPrice = price(route.costCredits, '次');
    return {
      label,
      mode,
      meterType,
      skuCount: 0,
      enabledSkuCount: 0,
      pricedSkuCount: fixedPrice ? 1 : 0,
      priceLabels: fixedPrice ? [fixedPrice] : [],
      hasIssue: !fixedPrice
    };
  }
  const skus: JsonRecord[] = Array.isArray(rule.skus) ? (rule.skus as unknown[]).filter(isRecord) : [];
  const enabledSkus = skus.filter((sku) => sku.enabled !== false);
  const priceLabels = enabledSkus.map((sku) => skuPriceLabel(sku, meterType));
  const pricedSkuCount = enabledSkus.filter((sku) => isSkuMainPriceConfigured(sku, meterType)).length;
  return {
    label,
    mode,
    meterType,
    skuCount: skus.length,
    enabledSkuCount: enabledSkus.length,
    pricedSkuCount,
    priceLabels: Array.from(new Set(priceLabels.filter((item) => item !== '价格未配置'))),
    hasIssue: enabledSkus.length === 0 || pricedSkuCount < enabledSkus.length
  };
}

export function summarizeCapabilityBilling(
  definition: ModelCapabilityDefinition,
  fallbackMeterType = inferMeterType(definition.generateMode)
): BillingOverviewData {
  const routes = definition.bindings
    .filter((route) => route.enabled !== false)
    .map((route, index) => summarizeRouteBilling(
      route,
      `${definition.label || definition.code} / ${route.protocol || `协议 ${index + 1}`}`,
      fallbackMeterType
    ));
  return aggregate(routes);
}

const aggregate = (routes: RouteBillingSummary[]): BillingOverviewData => ({
  routes,
  skuRouteCount: routes.filter((route) => route.mode === 'SKU').length,
  fixedRouteCount: routes.filter((route) => route.mode === 'FIXED').length,
  skuCount: routes.reduce((total, route) => total + route.skuCount, 0),
  enabledSkuCount: routes.reduce((total, route) => total + route.enabledSkuCount, 0),
  issueCount: routes.filter((route) => route.hasIssue).length
});

export function getModelBillingOverview(model: Partial<Model>): BillingOverviewData {
  const definitions = withEffectiveModelBilling(model).filter((definition) => definition.enabled !== false);
  if (definitions.length > 0) {
    const fallbackMeterType = inferMeterType(model.modelType || '');
    const overview = aggregate(definitions.flatMap((definition) => summarizeCapabilityBilling(definition, fallbackMeterType).routes));
    const uniqueSkus = new Map<string, JsonRecord>();
    for (const definition of definitions) {
      for (const route of definition.bindings) {
        if (route.enabled === false || route.billingMode !== 'SKU' || !isRecord(route.billingRule)) continue;
        const skus = Array.isArray(route.billingRule.skus) ? route.billingRule.skus : [];
        for (const rawSku of skus) {
          if (isRecord(rawSku)) {
            const code = typeof rawSku.skuCode === 'string' ? rawSku.skuCode.trim() : '';
            uniqueSkus.set(code || JSON.stringify(rawSku), rawSku);
          }
        }
      }
    }
    if (uniqueSkus.size > 0) {
      overview.skuCount = uniqueSkus.size;
      overview.enabledSkuCount = Array.from(uniqueSkus.values()).filter((sku) => sku.enabled !== false).length;
    }
    return overview;
  }
  let billingRule: JsonRecord = {};
  try {
    const parsed = model.billingRuleJson ? JSON.parse(model.billingRuleJson) : {};
    if (isRecord(parsed)) billingRule = parsed;
  } catch {
    billingRule = {};
  }
  return aggregate([summarizeRouteBilling({
    billingMode: model.billingMode,
    billingRule: billingRule as ModelProtocolBinding['billingRule'],
    costCredits: model.costCredits == null ? undefined : model.costCredits
  }, '默认调用协议', inferMeterType(model.modelType || ''))]);
}
