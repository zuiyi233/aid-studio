export interface BindingWithDefault {
  funcCode: string;
  defaultCapability: boolean;
}

// Each bound business needs exactly one default capability when saved.
export function ensureBusinessDefaults<T extends BindingWithDefault>(bindings: T[]): T[] {
  const defaults = new Map<string, number>();
  bindings.forEach((binding, index) => {
    if (binding.funcCode && binding.defaultCapability && !defaults.has(binding.funcCode)) {
      defaults.set(binding.funcCode, index);
    }
  });
  bindings.forEach((binding, index) => {
    if (binding.funcCode && !defaults.has(binding.funcCode)) defaults.set(binding.funcCode, index);
  });
  return bindings.map((binding, index) => ({
    ...binding,
    defaultCapability: Boolean(binding.funcCode) && defaults.get(binding.funcCode) === index,
  }));
}
