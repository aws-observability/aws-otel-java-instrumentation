#!/bin/bash
# Prints "true" if <tag> exists in the ADOT Java ECR repository, "false" if it does not.
# Exits non-zero if existence cannot be determined, so callers never mistake an error for "absent".
#
# Usage:
#   ecr-tag-exists.sh public <tag>    Anonymous registry API; needs no AWS credentials.
#   ecr-tag-exists.sh private <tag>   Needs AWS credentials with ecr:BatchGetImage in AWS_PRIVATE_ECR_REGION.
set -euo pipefail

PUBLIC_REPO_PATH="${PUBLIC_REPO_PATH:-aws-observability/adot-autoinstrumentation-java}"
PRIVATE_REPO_NAME="${PRIVATE_REPO_NAME:-adot-autoinstrumentation-java}"
MANIFEST_TYPES="application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json"

[[ $# -eq 2 ]] || { echo "usage: $0 {public|private} <tag>" >&2; exit 2; }
tag="$2"

case "$1" in
  public)
    token="$(curl -sfS --retry 3 "https://public.ecr.aws/token/?scope=repository:${PUBLIC_REPO_PATH}:pull" | jq -er .token)"
    code="$(curl -sS --retry 3 -o /dev/null -w '%{http_code}' -I \
      -H "Authorization: Bearer ${token}" -H "Accept: ${MANIFEST_TYPES}" \
      "https://public.ecr.aws/v2/${PUBLIC_REPO_PATH}/manifests/${tag}")"
    case "$code" in
      200) echo true ;;
      404) echo false ;;
      *) echo "Unexpected HTTP ${code} looking up public.ecr.aws/${PUBLIC_REPO_PATH}:${tag}" >&2; exit 1 ;;
    esac
    ;;
  private)
    # A missing tag is reported under "failures" with exit code 0; a missing repo or denied access exits non-zero.
    count="$(aws ecr batch-get-image --repository-name "${PRIVATE_REPO_NAME}" --image-ids imageTag="${tag}" \
      --region "${AWS_PRIVATE_ECR_REGION:?AWS_PRIVATE_ECR_REGION is required}" --query 'length(images)' --output text)"
    if [[ "$count" -gt 0 ]]; then echo true; else echo false; fi
    ;;
  *) echo "usage: $0 {public|private} <tag>" >&2; exit 2 ;;
esac
