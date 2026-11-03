#!/usr/bin/env bash

set -o errexit
set -o pipefail
set -o nounset
set -o xtrace

function find_sha() {
  local MODULE=$1
  local SOURCE_JSON ARCHIVE_URL REVISION
  SOURCE_JSON=$(
    jq -r '.registryFileHashes | keys[]' <<< "$LOCKFILE" |
      grep -F "/modules/$MODULE/" |
      grep '/source.json$'
  )

  ARCHIVE_URL=$(curl -fsSL "$SOURCE_JSON" | jq -er '.url')

  REVISION=${ARCHIVE_URL##*/}   # Keep only the filename, e.g. v1.8.0.tar.gz.
  REVISION=${REVISION%.tar.gz}  # Remove .tar.gz if present.
  REVISION=${REVISION%.zip}     # Remove .zip if present.
  echo "${REVISION#v}"         # Remove a leading v and print the revision for the caller.
}

function find_envoy_sha_from_tag() {
  local TAG=$1
  curl -fsSL "https://api.github.com/repos/envoyproxy/envoy/commits/$TAG" | awk -F'"' '/"sha"/ && !found {print $4; found=1}'
}

CURRENT_ENVOY_RELEASE=$(cat envoy_release)
ENVOY_VERSION=$(find_envoy_sha_from_tag "$1")

LOCKFILE=$(curl -fsSL "https://raw.githubusercontent.com/envoyproxy/envoy/$ENVOY_VERSION/api/MODULE.bazel.lock")

GOOGLEAPIS_SHA=$(find_sha googleapis)
PGV_GIT_SHA=$(find_sha protoc-gen-validate)
PROMETHEUS_SHA=$(find_sha prometheus-metrics-model)
XDS_SHA=$(find_sha xds)
# Envoy's API module still pins OpenTelemetry to 1.8.0, while its old
# metadata and our shipped protos use 1.10.0. Preserve the generated API
# while we investigate the version mismatch with the Envoy community.
OPENTELEMETRY_SHA="1.10.0"
# Envoy removed its direct CEL pin in envoyproxy/envoy#43265, so the API
# lockfile now selects 0.24.0. Keep 0.25.1 to preserve our generated CEL API
# while we clarify upstream whether api/ should restore that version.
CEL_SHA="0.25.1"

echo -n "# Update the versions here and run update-api.sh

# envoy (source: SHA from https://github.com/envoyproxy/envoy)
ENVOY_SHA=\"$ENVOY_VERSION\"

# dependencies (source: https://github.com/envoyproxy/envoy/blob/$ENVOY_VERSION/api/MODULE.bazel.lock)
GOOGLEAPIS_SHA=\"$GOOGLEAPIS_SHA\"
PGV_VERSION=\"$PGV_GIT_SHA\"
PROMETHEUS_SHA=\"$PROMETHEUS_SHA\"
OPENTELEMETRY_VERSION=\"$OPENTELEMETRY_SHA\"  # Pinned for API compatibility.
CEL_VERSION=\"$CEL_SHA\"  # Pinned for API compatibility.
XDS_SHA=\"$XDS_SHA\"
"

# replace version in EnvoyContainer.java
sed -i "s/$CURRENT_ENVOY_RELEASE/$1/g" ../server/src/test/java/io/envoyproxy/controlplane/server/EnvoyContainer.java

# update tag in envoy_release file
echo $1 > envoy_release
