/** IDE-neutral transport types used by the vendored loopback MCP server. */
export interface DebugToolInfo { name: string; description: string; inputSchema: Record<string, unknown>; }
export interface DebugToolOutcome { text: string; isError: boolean; }
