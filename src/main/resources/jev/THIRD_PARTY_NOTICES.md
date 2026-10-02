# Third-party notices

This repository ships the integration code and protocol schemas only. It does
not bundle a Jev executable, model weights, platform-specific resources, or a
Jev IDE plugin.

At runtime, `runtime/src/jev-client.ts` calls the TypeSafe AI System One HTTP
endpoint used by the local `dsh-jev` source (`https://api.typesafe.ai/v1/systemone`)
and sends the configured `jev-latest` model name. The service, its terms, and
any network or account charges are supplied by the operator. The operator must
provide `TYPESAFE_API_KEY` (or the existing `$HOME/.dsh/.env` convention); the
secret is never returned by this integration or persisted in its config file.

The runtime plugin is designed for a DSH Runtime that already provides
`@deepseek-ai/cordis` and the DSH Connection/Tools services. Those dependencies
remain the responsibility of the host runtime and are intentionally optional at
package-install time.
