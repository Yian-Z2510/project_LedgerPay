# LedgerPay production deployment

Pushes to `main` publish the backend, frontend, and webhook receiver images to
GHCR with the full Git commit SHA. The workflow stages the repository production
Compose file, preserves the currently deployed Compose as a rollback copy,
atomically activates the new file, and updates only `IMAGE_TAG` in the existing
`/opt/ledgerpay/.env`.

The deployment never replaces the production `.env`. It does not run
`docker compose down -v` or remove the PostgreSQL named volume. Rollback
restores both the previous production Compose file and the previous application
`IMAGE_TAG`; it does not reverse Flyway migrations. Any future schema migration
must remain backward-compatible with the previous application image or use a
forward-fix or staged migration strategy.

The `production` GitHub environment requires these secrets:

- `EC2_HOST`
- `EC2_USER`
- `EC2_SSH_PRIVATE_KEY`
- `EC2_SSH_KNOWN_HOSTS`

`EC2_SSH_KNOWN_HOSTS` must contain the EC2 SSH host key verified through a
trusted channel. Do not populate it from an unverified `ssh-keyscan` during the
deployment workflow.
