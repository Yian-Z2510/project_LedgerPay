# LedgerPay production deployment

Pushes to `main` publish the backend, frontend, and webhook receiver images to
GHCR with the full Git commit SHA. GitHub Actions uses OIDC to assume the AWS
deployment role and invokes the installed `/opt/ledgerpay/deploy-production.sh`
through AWS Systems Manager Run Command. The script updates only `IMAGE_TAG` in
the existing `/opt/ledgerpay/.env`.

The deployment never replaces the production `.env`. It does not run
`docker compose down -v` or remove the PostgreSQL named volume. Rollback
restores the previous application `IMAGE_TAG`; it does not reverse Flyway
migrations. Any future schema migration must remain backward-compatible with
the previous application image or use a forward-fix or staged migration
strategy.

Normal application CD is image-only. Changes to `compose.production.yaml`, host
Nginx, operating-system configuration, or `deploy-production.sh` must be
synchronized manually when they change.

The `production` GitHub environment requires this non-secret variable:

- `AWS_DEPLOY_ROLE_ARN`: the full ARN of `LedgerPayGitHubDeployRole`

The deployment uses short-lived GitHub OIDC credentials. It does not require
static AWS access keys or SSH credentials in GitHub.
