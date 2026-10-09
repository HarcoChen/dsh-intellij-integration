/** Adapt the explicit cmd.exe wrapper supplied by the IntelliJ host. */
export function runtimeSpawnArguments(
    command: string,
    args: string[],
    platform: NodeJS.Platform = process.platform,
): { args: string[]; windowsVerbatimArguments?: boolean } {
    if (platform !== 'win32' || !/(?:^|[\\/])cmd(?:\.exe)?$/iu.test(command)
        || args[0]?.toLowerCase() !== '/d' || args[1]?.toLowerCase() !== '/c'
        || args.length < 3) {
        return { args };
    }
    const launcher = args[2];
    if (/["%!\r\n]/u.test(launcher)) {
        throw new Error('DSH launcher path cannot be safely invoked through the Windows shell');
    }
    // Match dsh-ide #41: double backslashes before embedded and closing quotes.
    // /s removes the outer pair; each remaining argument keeps its own quotes.
    const quoted = args.slice(2).map(argument => `"${argument
        .replace(/(\\*)"/gu, (_match, slashes: string) => `${slashes}${slashes}""`)
        .replace(/\\+$/u, slashes => slashes + slashes)}"`);
    return {
        args: ['/d', '/s', '/c', `"${quoted.join(' ')}"`],
        windowsVerbatimArguments: true,
    };
}
