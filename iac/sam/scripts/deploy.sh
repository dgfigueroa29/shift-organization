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

PARAM_FILE="$IAM_DIR/parameters/$ENVIRONMENT.json"
TEMPLATE_FILE="$IAM_DIR/template.yaml"
STACK_NAME="shift-organization-$ENVIRONMENT"

if [ ! -f "$PARAM_FILE" ]; then
  echo "Parameter file not found: $PARAM_FILE" >&2
  exit 1
fi

echo "==> Packaging SAM artifacts for $ENVIRONMENT..."
sam package \
  --template-file "$TEMPLATE_FILE" \
  --s3-bucket "$S3_BUCKET" \
  --output-template-file "$IAM_DIR/packaged-$ENVIRONMENT.yaml"

echo "==> Deploying stack: $STACK_NAME"
sam deploy \
  --template-file "$IAM_DIR/packaged-$ENVIRONMENT.yaml" \
  --stack-name "$STACK_NAME" \
  --parameter-overrides "$(jq -r '.[] | "\(.ParameterKey)=\(.ParameterValue)"' "$PARAM_FILE" | tr '\n' ' ')" \
  --capabilities CAPABILITY_IAM CAPABILITY_AUTO_EXPAND \
  --no-fail-on-empty-changeset \
  --tags Environment="$ENVIRONMENT" Project=shift-organization

echo "==> Deploy complete"
