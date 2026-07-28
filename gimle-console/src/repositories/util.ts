export function delay<T>(value: T): Promise<T> {
  const ms = 200 + Math.random() * 400;
  return new Promise((resolve) => setTimeout(() => resolve(value), ms));
}

export function paginate<T>(
  all: T[],
  cursor: string | null,
  pageSize: number,
): { items: T[]; nextCursor: string | null } {
  const start = cursor ? parseInt(cursor, 10) || 0 : 0;
  const end = Math.min(start + pageSize, all.length);
  const items = all.slice(start, end);
  const nextCursor = end < all.length ? String(end) : null;
  return { items, nextCursor };
}
