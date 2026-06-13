#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IAM_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ENVIRONMENT="${1:-dev}"
S3_BUCKET="${2:-}"

if [ -z "$S3_BUCKET" ]; then
  echo "Usage: $0 <environment> <s3-bucket-name>" >&2
  echo "  environment: dev | staging | prod" >&2
  echo "  s3-bucket-name: S3 bucket for SAM artifacts" >&2
  exit 1
fi

TEMPLATE_FILE="$IAM_DIR/template.yaml"

echo "==> Packaging SAM artifacts to s3://$S3_BUCKET..."
sam package \
  --template-file "$TEMPLATE_FILE" \
  --s3-bucket "$S3_BUCKET" \
  --output-template-file "$IAM_DIR/packaged-$ENVIRONMENT.yaml"

echo "==> Package complete: $IAM_DIR/packaged-$ENVIRONMENT.yaml"
