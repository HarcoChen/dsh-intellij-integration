import { Terminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';
import '@xterm/xterm/css/xterm.css';
import './terminal-host.css';

declare global {
    interface Window {
        dshTerminalPost: (value: unknown) => void;
        dshTerminalReceive: (value: { type: string; data?: string; writable?: boolean; message?: string; scrollback?: number; viewEpoch?: number }) => void;
    }
}
const terminal = new Terminal({ cursorBlink: true, scrollback: 5000, fontSize: 13, allowProposedApi: false });
const fit = new FitAddon();
terminal.loadAddon(fit);
terminal.open(document.getElementById('terminal')!);
let writable = false;
window.dshTerminalReceive = frame => {
    if (frame.type === 'snapshot') {
        terminal.options.scrollback = Math.min(100000, Math.max(0, frame.scrollback ?? 5000));
    }
    if (frame.type === 'snapshot' || frame.type === 'output') {
        const data = frame.data ?? '';
        const bytes = new TextEncoder().encode(data).byteLength;
        terminal.write((frame.type === 'snapshot' ? '\x1bc' : '') + data, () => {
            window.dshTerminalPost({ type: 'rendered', bytes, epoch: frame.viewEpoch });
            if (frame.type === 'snapshot') { fit.fit(); terminal.focus(); }
        });
    }
    writable = frame.writable ?? writable;
    terminal.options.disableStdin = !writable;
    const status = document.getElementById('status')!;
    status.textContent = frame.message ?? (writable ? 'DSH Runtime' : 'DSH Runtime · read only');
};
terminal.onData(data => { if (writable) window.dshTerminalPost({ type: 'input', data }); });
terminal.onResize(({ cols, rows }) => window.dshTerminalPost({ type: 'resize', cols, rows }));
new ResizeObserver(() => fit.fit()).observe(document.getElementById('terminal')!);
fit.fit();
window.dshTerminalPost({ type: 'ready', cols: terminal.cols, rows: terminal.rows });
