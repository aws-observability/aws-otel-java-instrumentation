#!/bin/bash
# Stages, publishes or drops the Maven Central deployment of a release as separate steps, so the
# release workflow can validate Maven Central BEFORE pushing anything public and publish it AFTER.
#
# Uses only the documented OSSRH Staging API and Central Portal Publisher API:
#   https://central.sonatype.org/publish/publish-portal-ossrh-staging-api/
#   https://central.sonatype.org/publish/publish-portal-api/
#
# Usage:
#   maven-central-deployment.sh stage <version>
#       Uploads the open staging repository created by `./gradlew publishToSonatype` (same runner IP)
#       to the Central Portal as a USER_MANAGED deployment and waits for validation (signatures, POMs,
#       checksums). Nothing is published. Writes repository_key and deployment_id to $GITHUB_OUTPUT.
#   maven-central-deployment.sh publish <deployment_id>
#       Publishes a VALIDATED deployment to Maven Central. Irreversible once it reaches PUBLISHING.
#   maven-central-deployment.sh drop <repository_key> [deployment_id]
#       Cleans up an unpublished deployment: drops the staging repository and, if VALIDATED, the Portal
#       deployment. FAILED deployments are kept in the Portal for troubleshooting.
#   maven-central-deployment.sh is-published <version>
#       Exits 0 if the agent POM for <version> is on Maven Central, 1 if not, 2 if it cannot tell.
#       (Central can lag a successful publish by up to ~30 minutes; a re-stage in that window fails
#       validation, which is safe.)
#   maven-central-deployment.sh check-credentials
#       Read-only preflight: verifies the Portal token is accepted and warns about open staging repositories.
#
# Requires PUBLISH_TOKEN_USERNAME and PUBLISH_TOKEN_PASSWORD (Central Portal user token), except is-published.
set -euo pipefail

OSSRH_API="${OSSRH_API:-https://ossrh-staging-api.central.sonatype.com}"
PORTAL_API="${PORTAL_API:-https://central.sonatype.com}"
MAVEN_REPO="${MAVEN_REPO:-https://repo1.maven.org/maven2}"
POLL_INTERVAL="${POLL_INTERVAL:-15}"
VALIDATE_TIMEOUT="${VALIDATE_TIMEOUT:-1800}"
PUBLISH_TIMEOUT="${PUBLISH_TIMEOUT:-900}"
AGENT_PATH="software/amazon/opentelemetry/aws-opentelemetry-agent"

RESPONSE="$(mktemp)"
trap 'rm -f "$RESPONSE"' EXIT

log() { echo "$*" >&2; }
die() { echo "::error::$*" >&2; exit 1; }

# Sets AUTH. Not called in a subshell so the add-mask command reaches the runner on stdout.
init_auth() {
  : "${PUBLISH_TOKEN_USERNAME:?PUBLISH_TOKEN_USERNAME is required}"
  : "${PUBLISH_TOKEN_PASSWORD:?PUBLISH_TOKEN_PASSWORD is required}"
  local token
  token="$(printf '%s:%s' "$PUBLISH_TOKEN_USERNAME" "$PUBLISH_TOKEN_PASSWORD" | base64 | tr -d '\n')"
  if [[ -n "${GITHUB_ACTIONS:-}" ]]; then echo "::add-mask::$token"; fi
  AUTH="Authorization: Bearer $token"
}

# api METHOD URL -> prints HTTP status; body is left in $RESPONSE.
api() {
  curl -sS --retry 3 --retry-all-errors -o "$RESPONSE" -w '%{http_code}' -X "$1" -H "$AUTH" "$2"
}

expect() {
  local want="$1" got="$2" what="$3"
  [[ "$got" == "$want" ]] || die "$what failed: HTTP $got: $(cat "$RESPONSE")"
}

set_output() {
  log "$1=$2"
  if [[ -n "${GITHUB_OUTPUT:-}" ]]; then echo "$1=$2" >> "$GITHUB_OUTPUT"; fi
}

deployment_state() {
  expect 200 "$(api POST "$PORTAL_API/api/v1/publisher/status?id=$1")" "Deployment status lookup"
  jq -r '.deploymentState' "$RESPONSE"
}

stage() {
  local version="$1" key="" deployment_id="" state="" waited=0

  # The staging repository is bound to the IP that uploaded it, so search from this runner (ip=client).
  expect 200 "$(api GET "$OSSRH_API/manual/search/repositories?state=open&ip=client")" "Staging repository search"
  # Prefer repositories whose description (group:name:version) matches this version; require exactly one.
  key="$(jq -r --arg suffix ":$version" '
    [.repositories[] | select(.state == "open")] as $open
    | ([$open[] | select((.description // "") | endswith($suffix))]) as $matching
    | (if ($matching | length) > 0 then $matching else $open end)
    | if length == 1 then .[0].key else ("AMBIGUOUS:" + (map(.key) | join(","))) end' "$RESPONSE")"
  [[ "$key" == AMBIGUOUS:* || -z "$key" ]] && die "Expected exactly one open staging repository for $version, found: ${key#AMBIGUOUS:}. Drop stale ones with: $0 drop <key>"
  set_output repository_key "$key"

  log "Uploading staging repository $key to the Central Portal (publishing_type=user_managed, nothing is published)"
  expect 200 "$(api POST "$OSSRH_API/manual/upload/repository/$key?publishing_type=user_managed&deployment_name=aws-otel-java-instrumentation-$version")" "Upload to Central Portal"

  for _ in 1 2 3 4 5; do
    expect 200 "$(api GET "$OSSRH_API/manual/search/repositories?ip=client")" "Staging repository search"
    deployment_id="$(jq -r --arg key "$key" '.repositories[] | select(.key == $key) | .portal_deployment_id // empty' "$RESPONSE")"
    [[ -n "$deployment_id" ]] && break
    sleep "$POLL_INTERVAL"
  done
  [[ -n "$deployment_id" ]] || die "No Central Portal deployment ID found for staging repository $key"
  set_output deployment_id "$deployment_id"

  while true; do
    state="$(deployment_state "$deployment_id")"
    log "Deployment $deployment_id: $state"
    case "$state" in
      VALIDATED) log "Maven Central validation passed. Deployment is staged and NOT published."; return 0 ;;
      FAILED) jq . "$RESPONSE" >&2; die "Maven Central validation failed for $version (see errors above)" ;;
      PUBLISHING|PUBLISHED) die "Deployment $deployment_id is $state although it was uploaded as user_managed" ;;
    esac
    (( waited >= VALIDATE_TIMEOUT )) && die "Timed out after ${VALIDATE_TIMEOUT}s waiting for validation (last state: $state)"
    sleep "$POLL_INTERVAL"; waited=$(( waited + POLL_INTERVAL ))
  done
}

publish() {
  local deployment_id="$1" state="" waited=0
  state="$(deployment_state "$deployment_id")"
  [[ "$state" == "VALIDATED" ]] || die "Deployment $deployment_id is $state, expected VALIDATED"

  log "Publishing deployment $deployment_id to Maven Central"
  expect 204 "$(api POST "$PORTAL_API/api/v1/publisher/deployment/$deployment_id")" "Publish deployment"

  while true; do
    state="$(deployment_state "$deployment_id")"
    log "Deployment $deployment_id: $state"
    case "$state" in
      PUBLISHED) log "Published to Maven Central."; return 0 ;;
      FAILED) jq . "$RESPONSE" >&2; die "Publishing deployment $deployment_id failed (see errors above)" ;;
    esac
    if (( waited >= PUBLISH_TIMEOUT )); then
      [[ "$state" == "PUBLISHING" ]] || die "Timed out waiting for publish (last state: $state)"
      echo "::warning::Deployment $deployment_id is still PUBLISHING after ${PUBLISH_TIMEOUT}s. It is committed and will reach Maven Central; continuing."
      return 0
    fi
    sleep "$POLL_INTERVAL"; waited=$(( waited + POLL_INTERVAL ))
  done
}

drop() {
  local key="$1" deployment_id="${2:-}" state="" rc=0 code
  if [[ -n "$deployment_id" ]]; then
    state="$(deployment_state "$deployment_id")" || state="UNKNOWN"
    if [[ "$state" == "VALIDATED" ]]; then
      code="$(api DELETE "$PORTAL_API/api/v1/publisher/deployment/$deployment_id")"
      if [[ "$code" == "204" ]]; then log "Dropped Portal deployment $deployment_id"; else log "Could not drop Portal deployment $deployment_id: HTTP $code: $(cat "$RESPONSE")"; rc=1; fi
    else
      log "Leaving Portal deployment $deployment_id in state $state (only VALIDATED deployments are dropped)"
    fi
  fi
  code="$(api DELETE "$OSSRH_API/manual/drop/repository/$key")"
  if [[ "$code" == "204" ]]; then log "Dropped staging repository $key"; else log "Could not drop staging repository $key: HTTP $code: $(cat "$RESPONSE")"; rc=1; fi
  return "$rc"
}

is_published() {
  local version="$1" code
  code="$(curl -sS --retry 3 --retry-all-errors -o /dev/null -w '%{http_code}' -I "$MAVEN_REPO/$AGENT_PATH/$version/aws-opentelemetry-agent-$version.pom")"
  case "$code" in
    200) return 0 ;;
    404) return 1 ;;
    *) echo "::error::Unexpected HTTP $code checking Maven Central for $version" >&2; exit 2 ;;
  esac
}

check_credentials() {
  expect 200 "$(api GET "$OSSRH_API/manual/search/repositories?state=open&ip=any")" "Central Portal token check"
  log "Central Portal token accepted"
  local open
  open="$(jq -r '[.repositories[] | "\(.key) (\(.description // "no description"))"] | join(", ")' "$RESPONSE")"
  if [[ -n "$open" ]]; then echo "::warning::Open staging repositories already exist: $open. Stale ones can be dropped with: $0 drop <key>"; fi
}

cmd="${1:-}"; shift || true
case "$cmd" in
  stage)        [[ $# -eq 1 ]] || die "usage: $0 stage <version>"; init_auth; stage "$1" ;;
  publish)      [[ $# -eq 1 ]] || die "usage: $0 publish <deployment_id>"; init_auth; publish "$1" ;;
  drop)         [[ $# -ge 1 ]] || die "usage: $0 drop <repository_key> [deployment_id]"; init_auth; drop "$@" ;;
  is-published) [[ $# -eq 1 ]] || die "usage: $0 is-published <version>"; is_published "$1" ;;
  check-credentials) init_auth; check_credentials ;;
  *) die "usage: $0 {stage|publish|drop|is-published|check-credentials} ..." ;;
esac
