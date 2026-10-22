import { readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';

/** Guard every completion entry point before it posts an action or clears a draft. */
export async function adaptComposerAccess(root) {
    const patch = async (relative, replacements) => {
        const path = join(root, relative);
        let source = await readFile(path, 'utf8');
        for (const [anchor, replacement] of replacements) {
            if (!source.includes(anchor)) throw new Error(`Composer access adapter anchor changed: ${relative}`);
            source = source.replace(anchor, replacement);
        }
        await writeFile(path, source);
    };
    await patch('src/types.ts', [
        ['canRunCommands: boolean }', 'canRunCommands: boolean; canSelectPreset?: boolean; canReadSession?: boolean }'],
    ]);
    await patch('webview/src/components/Composer.tsx', [
        ['    const completion = useSlashCompletion({\n        text,', '    const completion = useSlashCompletion({\n        access,\n        text,'],
    ]);
    await patch('webview/src/components/useSlashCompletion.ts', [
        ['export interface SlashCompletionOptions {', 'export interface SlashCompletionOptions {\n    access?: ChatViewState["access"];'],
        ['export function useSlashCompletion({\n    text,', 'export function useSlashCompletion({\n    access,\n    text,'],
        ['    const executeSlashCommand = useCallback((name: string): boolean => {', [
            '    const executeSlashCommand = useCallback((name: string): boolean => {',
            '        const command = available.find((entry) => entry.name === name.toLowerCase().split(/\\s+/u)[0]);',
            '        const action = command?.action?.type;',
            '        // Treat denied commands as handled so send() cannot fall through and clear the draft.',
            '        if (command?.origin === "host" && (access?.canSend === false || access?.canRunCommands === false)) return true;',
            '        if ((action === "selectModel" || command?.name === "/effort") && access?.canSelectModel === false) return true;',
            '        if (action === "selectAgentPreset" && access?.canSelectPreset === false) return true;',
            '        if (action === "openTrace" && access?.canReadSession === false) return true;',
        ].join('\n')],
        ['}, [reasoningEffort, onShowEffort, setText, available]);', '}, [access, reasoningEffort, onShowEffort, setText, available]);'],
        ['        } else if (/^\\/\\S*$/u.test(text)) {\n            postAction(', '        } else if (/^\\/\\S*$/u.test(text)) {\n            if (access?.canSend === false) return;\n            postAction('],
        ['}, [text, setText, focusTextarea]);', '}, [access?.canSend, text, setText, focusTextarea]);'],
    ]);
}
