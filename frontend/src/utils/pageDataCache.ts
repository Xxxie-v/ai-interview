interface CacheEntry<T> {
  value?: T;
  expiresAt: number;
  pending?: Promise<T>;
}

const cache = new Map<string, CacheEntry<unknown>>();

export function readPageData<T>(key: string): T | null {
  const entry = cache.get(key) as CacheEntry<T> | undefined;
  return entry?.value ?? null;
}

export function writePageData<T>(key: string, value: T, ttlMs = 60_000): T {
  cache.set(key, {value, expiresAt: Date.now() + ttlMs});
  return value;
}

export function loadPageData<T>(
  key: string,
  loader: () => Promise<T>,
  options: {force?: boolean; ttlMs?: number} = {},
): Promise<T> {
  const entry = cache.get(key) as CacheEntry<T> | undefined;
  if (!options.force) {
    if (entry?.value && entry.expiresAt > Date.now()) return Promise.resolve(entry.value);
    if (entry?.pending) return entry.pending;
  }

  const pending = loader()
    .then(value => writePageData(key, value, options.ttlMs))
    .catch(error => {
      if (cache.get(key) === nextEntry) {
        if (entry?.value) {
          cache.set(key, entry);
        } else {
          cache.delete(key);
        }
      }
      throw error;
    });
  const nextEntry: CacheEntry<T> = {
    value: entry?.value,
    expiresAt: entry?.expiresAt ?? 0,
    pending,
  };
  cache.set(key, nextEntry);
  return pending;
}
