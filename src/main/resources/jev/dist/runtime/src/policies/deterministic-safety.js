function boundedJson(value, maxChars) {
    try {
        const serialized = JSON.stringify(value);
        const text = typeof serialized === 'string' ? serialized : String(value);
        return text.length > maxChars ? text.slice(0, maxChars) : text;
    }
    catch {
        return String(value).slice(0, maxChars);
    }
}
const RULES = [
    { category: 'destructive', pattern: /(?:^|[\s;&|])rm\s+(?:-[a-z]*f[a-z]*\s+)?(?:-[a-z]*r[a-z]*\s+)?(?:\/|~\/|\.\/|\.\.\/)/i, reason: 'destructive recursive deletion' },
    { category: 'destructive', pattern: /(?:mkfs(?:\.[a-z0-9]+)?|dd\s+if=|\bformat\s+[a-z]:|diskpart\b|wipefs\b)/i, reason: 'destructive disk operation' },
    { category: 'destructive', pattern: /git\s+(?:reset\s+--hard|clean\s+-[^\n]*f)|(?:drop|truncate)\s+(?:database|table)\b|:\(\)\s*\{\s*:\|:\s*&\s*\}/i, reason: 'destructive repository or database operation' },
    { category: 'privilege', pattern: /(?:^|[\s;&|"'`])(?:sudo|doas|runas)\b|--privileged\b|chmod\s+[ugoa]*\+s\b|chown\s+(?:root|0)\b/i, reason: 'privilege elevation or setuid change' },
    { category: 'privilege', pattern: /(?:^|[\s"'])\/(?:etc|usr|bin|sbin|var\/lib|system|windows)\//i, reason: 'write to a protected system path' },
    { category: 'credential', pattern: /(?:TYPESAFE_API_KEY|OPENAI_API_KEY|ANTHROPIC_API_KEY|AWS_SECRET_ACCESS_KEY|PRIVATE KEY|BEGIN [A-Z ]+ PRIVATE KEY|\.ssh\/authorized_keys)/i, reason: 'credential or private key exposure' },
    { category: 'credential', pattern: /(?:cat|type|Get-Content|printenv|env)\s+[^\n]*(?:\.env|\.ssh|credentials|token|secret|password)/i, reason: 'reading a credential-bearing file or environment' },
    { category: 'credential', pattern: /(?:curl|wget)\s+[^\n]*(?:\$[A-Z][A-Z0-9_]*|Authorization:|Bearer\s+)/i, reason: 'sending an environment credential to a remote endpoint' },
];
export function evaluateDeterministicSafety(execution, guardedTools, config) {
    if (!config.enabled)
        return undefined;
    const name = typeof execution.name === 'string' ? execution.name : '';
    if (!guardedTools.includes(name))
        return undefined;
    const raw = execution.arguments !== undefined ? execution.arguments : execution.args;
    const serialized = boundedJson(raw, config.maxArgumentChars);
    for (const rule of RULES) {
        if (rule.pattern.test(serialized))
            return { category: rule.category, reason: rule.reason };
    }
    return undefined;
}
export function installDeterministicSafetyGuard(tools, guardedTools, config, onCheck) {
    if (!tools || typeof tools.guard !== 'function' || !config.enabled)
        return undefined;
    const result = tools.guard((execution) => {
        const match = evaluateDeterministicSafety(execution, guardedTools, config);
        onCheck?.(execution, match);
        return match === undefined ? undefined : `[DSH Jev deterministic safety] ${match.reason}. Review the command and request explicit approval.`;
    });
    return typeof result === 'function' ? result : undefined;
}
//# sourceMappingURL=deterministic-safety.js.map