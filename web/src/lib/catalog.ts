/** Game content (monsters/resources/items) loaded once and shared by every picker. */
import { api } from './api'
import type { Catalog } from './types'

let cached: Promise<Catalog> | null = null

export function catalog(): Promise<Catalog> {
  // The first caller triggers the fetch; later callers reuse the same promise.
  cached ??= api.content().catch((e) => { cached = null; throw e })
  return cached
}
