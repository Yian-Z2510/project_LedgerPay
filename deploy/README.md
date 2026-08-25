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

## Production hardening files

These host-level files are deployed manually; normal application CD remains
image-only:

- `compose.production.yaml`: the four services, persistent PostgreSQL volume,
  internal network, loopback-only frontend port, and bounded Docker logs.
- `deploy/nginx/conf.d/ledgerpay-rate-limit.conf`: the standalone HTTP-level
  Merchant-creation rate limit.
- `deploy/nginx/ledgerpay.yianz.me.conf`: the LedgerPay virtual host.
- `deploy/cleanup-demo-data.sh` and `deploy/sql/cleanup-demo-data.sql`: the
  guarded cleanup command and SQL transaction.
- `deploy/cron/ledgerpay-demo-cleanup`: the daily schedule.

Do not replace or edit the live Certbot-managed server block. Install the
standalone HTTP-level rate-limit configuration, validate, then reload Nginx:

```bash
sudo install -m 0644 deploy/nginx/conf.d/ledgerpay-rate-limit.conf \
  /etc/nginx/conf.d/ledgerpay-rate-limit.conf
sudo nginx -t
sudo systemctl reload nginx
```

Only `POST /api/v1/merchants` consumes the per-client-IP zone. The sustained
rate is 5 requests per minute with a burst of 3, and rejected requests return
HTTP 429. Other methods and paths continue through the normal proxy location.

## Demo-data retention

The cleanup targets a merchant only when all production demo markers match:

- name is exactly `LedgerPay Demo`;
- email matches the browser-generated `ledgerpay-demo-<time>-<uuid>@example.com`
  format;
- webhook URL is exactly `http://webhook-receiver:9000/webhook`;
- merchant `created_at` is older than the retention period.

The default is a read-only dry run. The minimum accepted retention period is 7
days. The SQL uses one transaction and a transaction-scoped advisory lock. It
deletes in the foreign-key-safe order `webhook_event`, `refund`, `payment`,
`merchant_order`, then `merchant`. Repeating it is safe because rows already
removed are no longer selected.

Install the files without copying `.env` from the repository:

```bash
sudo install -o ubuntu -g ubuntu -m 0755 deploy/cleanup-demo-data.sh \
  /opt/ledgerpay/cleanup-demo-data.sh
sudo install -d -o ubuntu -g ubuntu -m 0755 /opt/ledgerpay/sql
sudo install -o ubuntu -g ubuntu -m 0644 deploy/sql/cleanup-demo-data.sql \
  /opt/ledgerpay/sql/cleanup-demo-data.sql
sudo install -o root -g root -m 0644 deploy/cron/ledgerpay-demo-cleanup \
  /etc/cron.d/ledgerpay-demo-cleanup
```

Run and review a dry run before enabling or manually executing deletion:

```bash
sudo -H -u ubuntu /opt/ledgerpay/cleanup-demo-data.sh --dry-run --retention-days 7
sudo -H -u ubuntu /opt/ledgerpay/cleanup-demo-data.sh --execute --retention-days 7
sudo journalctl -t ledgerpay-demo-cleanup --since "2 days ago"
```

Do not enable the cron entry until an off-instance database backup and restore
procedure has been verified. The named volume survives container recreation;
it does not protect against EC2/EBS loss or operator deletion.

## Security and runtime checks

The EC2 security group should expose only TCP 80 and 443 publicly. TCP 22, if
retained for emergency access, must be restricted to the owner's current IP;
normal deployment uses OIDC and SSM, not SSH. Ports 5432, 8080 (backend), 9000,
and the frontend upstream on host port 8080 must not be public. Confirm both AWS
rules and host listeners because Docker Compose alone cannot prove the security
group configuration:

```bash
sudo ss -lntp
docker compose --env-file .env -f compose.production.yaml ps
docker network inspect ledgerpay_app-internal
```

PostgreSQL uses the `postgres-data` named volume. Every service uses
`restart: unless-stopped`; PostgreSQL has a healthcheck, backend waits for a
healthy database, and frontend waits for a healthy backend. Deployments must
never use `docker compose down -v` or delete the volume. Docker JSON logs are
bounded to three 10 MiB files per service.

TLS terminates in host Nginx. Certbot owns certificate paths and renewal; TLS is
not copied into Docker images. Verify the live certificate and timer on EC2:

```bash
sudo nginx -t
sudo certbot certificates
sudo certbot renew --dry-run
systemctl status certbot.timer
curl --fail --show-error https://ledgerpay.yianz.me/health
```

## Lightweight operations

Useful read-only troubleshooting commands:

```bash
cd /opt/ledgerpay
docker compose --env-file .env -f compose.production.yaml ps
docker compose --env-file .env -f compose.production.yaml logs --tail 200 backend
docker compose --env-file .env -f compose.production.yaml logs --tail 200 frontend
docker compose --env-file .env -f compose.production.yaml logs --tail 200 postgres
docker compose --env-file .env -f compose.production.yaml logs --tail 200 webhook-receiver
sudo systemctl status nginx
sudo journalctl -u nginx --since "30 minutes ago"
sudo systemctl status amazon-ssm-agent
```

Known limitations: this is a single EC2 host with brief in-place deployment
downtime, no managed RDS or automated off-instance database backup, no automatic
application-data cleanup beyond the identified browser Demo Merchants, and no
distributed rate-limit state. Application/Compose rollback does not undo
Flyway migrations; future migrations must be backward-compatible or use a
forward-fix/staged strategy.
