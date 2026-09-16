#!/usr/bin/env bash
# Idempotent Cloud Agent bootstrap for the Roatz repo.
#
#   * Builds the Java agent JAR + Dynamic Attach driver (warms the Gradle
#     wrapper and Maven Central dependency cache).
#   * Installs the Cloudflare Worker (license-server) Node dependencies.
#   * Seeds the Worker's local dev secrets from the committed example so
#     `wrangler dev` can start without a Cloudflare account.
#
# Windows-only artifacts (jpackage image, Inno Setup installer) and running the
# agent against the proprietary Roat PKz client (game.jar) are out of scope on
# Linux and are intentionally not built here.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo "==> Building agent JAR + attach driver (gradlew buildAll)"
./gradlew --no-daemon buildAll

echo "==> Installing license-server dependencies (npm ci)"
(cd license-server && npm ci)

echo "==> Seeding license-server/.dev.vars for local wrangler dev"
if [ ! -f license-server/.dev.vars ]; then
  cp license-server/.dev.vars.example license-server/.dev.vars
fi

echo "==> Bootstrap complete"
