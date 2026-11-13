#!/bin/bash
# Release preflight for Maven Central signing. Checks that GPG_PRIVATE_KEY / GPG_PASSPHRASE can sign,
# that the key is not revoked or expired, and that its public key is on a keyserver Maven Central
# validates against. (v2.33.0 failed Central validation with "Could not find a public key by the key
# fingerprint" after ECR images were already public.)
#
# Fails if the key cannot sign, is revoked/expired, or is definitively missing (HTTP 404) from every
# keyserver. Keyserver outages only produce warnings, so a flaky keyserver does not block a release.
set -euo pipefail

: "${GPG_PRIVATE_KEY:?GPG_PRIVATE_KEY is required}"
: "${GPG_PASSPHRASE:?GPG_PASSPHRASE is required}"
KEYS_OPENPGP_URL="${KEYS_OPENPGP_URL:-https://keys.openpgp.org/vks/v1/by-fingerprint/}"
KEYSERVER_UBUNTU_URL="${KEYSERVER_UBUNTU_URL:-https://keyserver.ubuntu.com/pks/lookup?op=get&options=mr&search=0x}"
EXPIRY_WARNING_DAYS="${EXPIRY_WARNING_DAYS:-30}"

GNUPGHOME="$(mktemp -d)"
export GNUPGHOME
trap 'gpgconf --kill gpg-agent >/dev/null 2>&1 || true; rm -rf "$GNUPGHOME"' EXIT

printf '%s\n' "$GPG_PRIVATE_KEY" | gpg --batch --quiet --import 2>/dev/null \
  || { echo "::error::GPG_PRIVATE_KEY could not be imported"; exit 1; }

sec_line="$(gpg --batch --with-colons --list-secret-keys | awk -F: '$1 == "sec" { print; exit }')"
fingerprint="$(gpg --batch --with-colons --list-secret-keys | awk -F: '$1 == "fpr" { print $10; exit }')"
[[ -n "$sec_line" && -n "$fingerprint" ]] || { echo "::error::No secret key found in GPG_PRIVATE_KEY"; exit 1; }
echo "Signing key fingerprint: ${fingerprint}"

validity="$(cut -d: -f2 <<< "$sec_line")"
expires="$(cut -d: -f7 <<< "$sec_line")"
now="$(date +%s)"
[[ "$validity" == "r" ]] && { echo "::error::Signing key ${fingerprint} is revoked"; exit 1; }
if [[ -n "$expires" ]]; then
  (( expires <= now )) && { echo "::error::Signing key ${fingerprint} expired at $(date -u -d "@${expires}" 2>/dev/null || echo "${expires}")"; exit 1; }
  (( expires - now < EXPIRY_WARNING_DAYS * 86400 )) && echo "::warning::Signing key ${fingerprint} expires within ${EXPIRY_WARNING_DAYS} days; rotate it and upload the new public key to keyservers"
fi

# Proves the passphrase unlocks the key, without exposing it on the command line.
echo "preflight" | gpg --batch --yes --pinentry-mode loopback --passphrase-fd 3 --detach-sign -o "$GNUPGHOME/test.sig" 3<<< "$GPG_PASSPHRASE" 2>/dev/null \
  || { echo "::error::GPG_PASSPHRASE does not unlock GPG_PRIVATE_KEY (test signature failed)"; exit 1; }
echo "Test signature succeeded"

found=0 missing=0 errors=0
for url in "${KEYS_OPENPGP_URL}${fingerprint}" "${KEYSERVER_UBUNTU_URL}${fingerprint}"; do
  code="$(curl -sS -m 20 --retry 3 -o /dev/null -w '%{http_code}' "$url" 2>/dev/null)" || code="000"
  case "$code" in
    200) echo "Public key found: ${url}"; found=$((found + 1)) ;;
    404) echo "::warning::Public key NOT found: ${url}"; missing=$((missing + 1)) ;;
    *)   echo "::warning::Keyserver lookup failed (HTTP ${code}): ${url}"; errors=$((errors + 1)) ;;
  esac
done

if (( found == 0 && errors == 0 )); then
  echo "::error::Public key ${fingerprint} is on no supported keyserver; Maven Central validation will fail. Upload it (e.g. gpg --keyserver keyserver.ubuntu.com --send-keys ${fingerprint})"
  exit 1
fi
(( found == 0 )) && echo "::warning::Could not confirm the public key is on any keyserver (lookups failed); continuing"
exit 0
