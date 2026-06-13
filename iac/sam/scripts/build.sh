#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"
ENVIRONMENT="${1:-dev}"

echo "==> Building all lambda modules for environment: $ENVIRONMENT"
cd "$PROJECT_DIR"

./gradlew :lambda-properties:shadowJar \
  :lambda-bookings:shadowJar \
  :lambda-recurring-events:shadowJar \
  :lambda-notifications:shadowJar \
  :lambda-health:shadowJar \
  :lambda-recurring-processor:shadowJar \
  --no-daemon --quiet

echo "==> Verifying artifacts..."
for module in properties bookings recurring-events notifications health recurring-processor; do
  jar="$PROJECT_DIR/lambda-$module/build/libs/$module.jar"
  if [ -f "$jar" ]; then
    echo "  OK: $jar ($(du -h "$jar" | cut -f1))"
  else
    echo "  MISSING: $jar" >&2
    exit 1
  fi
done

echo "==> Build complete"
