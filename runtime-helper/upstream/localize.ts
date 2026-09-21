export function t(value: string, args: Record<string, unknown> = {}): string { return value.replace(/\{([^}]+)\}/g, (match, key) => key in args ? String(args[key]) : match); }
