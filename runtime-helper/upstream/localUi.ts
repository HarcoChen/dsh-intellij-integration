import { randomUUID } from 'node:crypto';
const pending = new Map<string, (value: string | undefined) => void>();
export function acceptReply(message: any) { if (message.type === 'prompt-result') {
    pending.get(message.id)?.(message.value);
    pending.delete(message.id);
} }
export function prompt(message: string, options: string[], detail?: string): Promise<string | undefined> { const id = randomUUID(); process.stdout.write('\nDSH_INTELLIJ_HELPER ' + JSON.stringify({ event: 'prompt', value: { id, message, detail, options } }) + '\n'); return new Promise(resolve => pending.set(id, resolve)); }
export const ProgressLocation = { Notification: 1 };
export const window = {
    showWarningMessage(message: string, config: {
        modal?: boolean;
        detail?: string;
    }, ...options: string[]) { return prompt(message, options, config.detail); },
    async withProgress<T>(_options: unknown, task: (progress: unknown, token: {
        isCancellationRequested: boolean;
        onCancellationRequested: (callback: () => void) => {
            dispose: () => void;
        };
    }) => Promise<T>): Promise<T> { return task({}, { isCancellationRequested: false, onCancellationRequested: () => ({ dispose() { } }) }); }
};
export const env = { clipboard: { async writeText(text: string) { process.stdout.write('\nDSH_INTELLIJ_HELPER ' + JSON.stringify({ event: 'clipboard', value: { text } }) + '\n'); } } };
