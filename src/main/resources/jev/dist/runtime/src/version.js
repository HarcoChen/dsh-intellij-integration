import { INTEGRATION_VERSION, MIN_DSH_RUNTIME_VERSION, PROTOCOL_VERSION, } from '../../protocol/src/index.js';
export const VERSION = {
    integration: INTEGRATION_VERSION,
    protocol: PROTOCOL_VERSION,
    minimumDshRuntime: MIN_DSH_RUNTIME_VERSION,
    // A release build may inject this value. Keeping an explicit sentinel makes
    // an uncommitted/local build visible to hosts instead of inventing a hash.
    commit: process.env.DSH_JEV_INTEGRATION_COMMIT ?? 'unresolved',
};
//# sourceMappingURL=version.js.map