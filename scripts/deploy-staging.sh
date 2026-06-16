#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

if [ ! -f .env ]; then
  cp .env.example .env
  echo "Created .env from .env.example — edit before production deploy"
fi

# Generate JWT keys if missing
if ! grep -q "BEGIN RSA PRIVATE KEY" .env 2>/dev/null; then
  echo "Generating JWT keys..."
  PRIVATE=$(openssl genrsa 2048 2>/dev/null | awk '{printf "%s\\n", $0}' | head -c -2)
  PUBLIC=$(echo "$PRIVATE" | sed 's/\\n/\n/g' | openssl rsa -pubout 2>/dev/null | awk '{printf "%s\\n", $0}' | head -c -2)
  echo "JWT_PRIVATE_KEY=\"$PRIVATE\"" >> .env
  echo "JWT_PUBLIC_KEY=\"$PUBLIC\"" >> .env
fi

# Generate Tatarlar secret if missing
if ! grep -q "^TATARLAR_CLIENT_SECRET=.\+" .env 2>/dev/null; then
  SECRET=$(openssl rand -base64 32 | tr -d '/+=' | head -c 32)
  echo "TATARLAR_CLIENT_SECRET=$SECRET" >> .env
  echo "Generated TATARLAR_CLIENT_SECRET"
fi

docker compose pull
docker compose up -d --build

echo ""
echo "=== Deploy complete ==="
echo "Check: curl -s http://localhost/health"
echo "Tatarlar client_secret: grep TATARLAR_CLIENT_SECRET .env"
