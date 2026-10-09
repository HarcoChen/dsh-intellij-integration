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
    { id: 'builtin.destructive-recursive', category: 'destructive', pattern: /(?:^|[\s;&|])rm\s+(?:-[a-z]*f[a-z]*\s+)?(?:-[a-z]*r[a-z]*\s+)?(?:\/|~\/|\.\/|\.\.\/)/i, reason: 'destructive recursive deletion' },
    { id: 'builtin.destructive-disk', category: 'destructive', pattern: /(?:mkfs(?:\.[a-z0-9]+)?|dd\s+if=|\bformat\s+[a-z]:|diskpart\b|wipefs\b)/i, reason: 'destructive disk operation' },
    { id: 'builtin.destructive-repository', category: 'destructive', pattern: /git\s+(?:reset\s+--hard|clean\s+-[^\n]*f)|(?:drop|truncate)\s+(?:database|table)\b|:\(\)\s*\{\s*:\|:\s*&\s*\}/i, reason: 'destructive repository or database operation' },
    { id: 'builtin.privilege-elevation', category: 'privilege', pattern: /(?:^|[\s;&|"'`])(?:sudo|doas|runas)\b|--privileged\b|chmod\s+[ugoa]*\+s\b|chown\s+(?:root|0)\b/i, reason: 'privilege elevation or setuid change' },
    { id: 'builtin.protected-path', category: 'privilege', pattern: /(?:^|[\s"'])\/(?:etc|usr|bin|sbin|var\/lib|system|windows)\//i, reason: 'write to a protected system path' },
    { id: 'builtin.private-key', category: 'credential', pattern: /(?:PRIVATE KEY|BEGIN [A-Z ]+ PRIVATE KEY|\.ssh\/authorized_keys|id_(?:rsa|ed25519|ecdsa))/i, reason: 'private key exposure', credentialClass: 'private-key' },
    { id: 'builtin.credential-name', category: 'credential', pattern: /(?:TYPESAFE_API_KEY|OPENAI_API_KEY|ANTHROPIC_API_KEY|AWS_SECRET_ACCESS_KEY|GITHUB_TOKEN|NPM_TOKEN)/i, reason: 'credential or token exposure', credentialClass: 'suspected-credential' },
    { id: 'builtin.credential-file', category: 'credential', pattern: /(?:cat|type|Get-Content|printenv|env)\s+[^\n]*(?:\.env|\.ssh|credentials|token|secret|password)/i, reason: 'reading a credential-bearing file or environment', credentialClass: 'suspected-credential' },
    { id: 'builtin.credential-egress', category: 'credential', pattern: /(?:curl|wget)\s+[^\n]*(?:\$[A-Z][A-Z0-9_]*|Authorization:|Bearer\s+)/i, reason: 'sending an environment credential to a remote endpoint', credentialClass: 'suspected-credential' },
    { id: 'builtin.real-secret-assignment', category: 'credential', pattern: /(?:api[_-]?key|secret|password|token)\s*[:=]\s*["']?(?!your[_ -]?|example|sample|test|changeme|placeholder|<)[A-Za-z0-9_./+=-]{16,}/i, reason: 'probable credential value in tool arguments', credentialClass: 'real-credential' },
];
const PLACEHOLDER_PATTERN = /(?:<[^>]*(?:key|token|secret|password|credential)[^>]*>|\b(?:your|example|sample|test|dummy|changeme|placeholder)[_-]?(?:api[_-]?key|token|secret|password|credential)\b|\$\{?[A-Z][A-Z0-9_]*\}?)/i;
export function classifyCredential(value) {
    if (PLACEHOLDER_PATTERN.test(value))
        return 'placeholder';
    if (/(?:PRIVATE KEY|BEGIN [A-Z ]+ PRIVATE KEY|\.ssh\/authorized_keys|id_(?:rsa|ed25519|ecdsa))/i.test(value))
        return 'private-key';
    if (/(?:api[_-]?key|secret|password|token)\s*[:=]\s*["']?[A-Za-z0-9_./+=-]{16,}/i.test(value))
        return 'real-credential';
    if (/(?:TYPESAFE_API_KEY|OPENAI_API_KEY|ANTHROPIC_API_KEY|AWS_SECRET_ACCESS_KEY|GITHUB_TOKEN|NPM_TOKEN|Bearer\s+|\.env|credentials)/i.test(value))
        return 'suspected-credential';
    return undefined;
}
function customRules(config) {
    return config.customRules.filter(rule => rule.enabled !== false && typeof rule.pattern === 'string');
}
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
            return { category: rule.category, reason: rule.reason, ruleId: rule.id, ...(rule.credentialClass !== undefined ? { credentialClass: rule.credentialClass } : {}) };
    }
    for (const rule of customRules(config)) {
        try {
            if (!new RegExp(rule.pattern, 'i').test(serialized))
                continue;
            if (rule.credentialClass === 'placeholder')
                continue;
            return {
                category: rule.category,
                reason: rule.reason,
                ruleId: rule.id,
                ...(rule.credentialClass !== undefined ? { credentialClass: rule.credentialClass } : {}),
            };
        }
        catch {
            // Config validation rejects malformed rules; persisted legacy config is
            // ignored here rather than making the ToolRuntime unusable.
        }
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