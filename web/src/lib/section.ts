export type Section<T> = { status: 'ok'; data: T } | { status: 'unavailable' };
