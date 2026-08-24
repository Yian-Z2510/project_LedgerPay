#!/usr/bin/env python3

import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Receive local LedgerPay Webhooks and return a chosen HTTP status."
    )
    parser.add_argument(
        "--status",
        type=int,
        choices=range(200, 600),
        default=204,
        metavar="200-599",
        help="HTTP response status to return (default: 204)",
    )
    parser.add_argument(
        "--host",
        default="127.0.0.1",
        help="Host interface to bind (default: 127.0.0.1)",
    )
    parser.add_argument(
        "--port",
        type=int,
        choices=range(1, 65536),
        default=9000,
        metavar="1-65535",
        help="Port to listen on (default: 9000)",
    )
    return parser.parse_args()


def create_handler(response_status: int) -> type[BaseHTTPRequestHandler]:
    class WebhookHandler(BaseHTTPRequestHandler):
        def do_POST(self) -> None:
            if self.path != "/webhook":
                self.send_error(404)
                return

            content_length = int(self.headers.get("Content-Length", "0"))
            remaining = content_length
            while remaining > 0:
                chunk = self.rfile.read(min(remaining, 64 * 1024))
                if not chunk:
                    break
                remaining -= len(chunk)

            print(
                f"Webhook received: POST {self.path} "
                f"({content_length} bytes) -> HTTP {response_status}",
                flush=True,
            )

            self.send_response(response_status)
            self.send_header("Content-Length", "0")
            self.end_headers()

        def log_message(self, format: str, *args: object) -> None:
            return

    return WebhookHandler


def main() -> None:
    args = parse_args()
    server = ThreadingHTTPServer(
        (args.host, args.port),
        create_handler(args.status),
    )

    print(
        f"Mock Webhook receiver listening at http://{args.host}:{args.port}/webhook "
        f"and returning HTTP {args.status}.",
        flush=True,
    )
    print("Press Ctrl+C to stop.", flush=True)

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nMock Webhook receiver stopped.", flush=True)
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
