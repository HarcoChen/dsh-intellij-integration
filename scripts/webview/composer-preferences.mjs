import { readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';

/** Apply host-owned composer preferences to the pinned companion sources. */
export async function adaptComposerPreferences(root) {
    const patch = async (relative, replacements) => {
        const path = join(root, relative);
        let source = await readFile(path, 'utf8');
        for (const [anchor, replacement] of replacements) {
            if (!source.includes(anchor)) throw new Error(`Composer preference adapter anchor changed: ${relative}`);
            source = source.replace(anchor, replacement);
        }
        await writeFile(path, source);
    };
    await patch('src/types.ts', [
        ['    autoOpenReasoning?: boolean;', '    autoOpenReasoning?: boolean;\n    enterToSend?: boolean;'],
    ]);
    await patch('webview/src/state.ts', [
        ['    | "selectionEnabled"', '    | "selectionEnabled"\n    | "enterToSend"'],
    ]);
    await patch('webview/src/App.tsx', [
        ['                autoOpenReasoning={state.autoOpenReasoning}', '                autoOpenReasoning={state.autoOpenReasoning}\n                enterToSend={state.enterToSend}'],
        ['                selectionEnabled={state.selectionEnabled}', '                selectionEnabled={state.selectionEnabled}\n                enterToSend={state.enterToSend}'],
    ]);
    await patch('webview/src/components/Composer.tsx', [
        ['    selectionEnabled: ComposerState["selectionEnabled"];', '    selectionEnabled: ComposerState["selectionEnabled"];\n    enterToSend: ComposerState["enterToSend"];'],
        ['    selectionEnabled,', '    selectionEnabled,\n    enterToSend = false,'],
        ['                    onKeyDown={(event) => {', [
            '                    onKeyDown={(event) => {',
            '                        if (event.nativeEvent.isComposing || event.nativeEvent.keyCode === 229) return;',
            '                        if (enterToSend && event.key === "Enter" && event.shiftKey && !event.ctrlKey && !event.metaKey) return;',
        ].join('\n')],
        ['                        if ((event.ctrlKey || event.metaKey) && event.key === "Enter") {\n                            event.preventDefault();\n                            send();\n                        }', [
            '                        if (event.key === "Enter" && (',
            '                            event.ctrlKey || event.metaKey ||',
            '                            (enterToSend && !event.shiftKey && !event.altKey)',
            '                        )) {',
            '                            event.preventDefault();',
            '                            if (!event.repeat) send();',
            '                        }',
        ].join('\n')],
        ['                    rows={2}', [
            '                    rows={2}',
            '                    title={t(enterToSend',
            '                        ? "Enter to send; Shift+Enter for a new line."',
            '                        : "Ctrl/Cmd + Enter to send.")}',
        ].join('\n')],
    ]);
    await patch('webview/src/components/MessageList.tsx', [
        ['    autoOpenReasoning?: boolean;', '    autoOpenReasoning?: boolean;\n    enterToSend?: boolean;'],
        ['    autoOpenReasoning,', '    autoOpenReasoning,\n    enterToSend = false,'],
        ['{t("Ctrl/Cmd + Enter to send.")}', '{t(enterToSend ? "Enter to send; Shift+Enter for a new line." : "Ctrl/Cmd + Enter to send.")}'],
    ]);
}
