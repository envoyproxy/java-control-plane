#!/usr/bin/env bash

set -o errexit
set -o pipefail
set -o nounset
set -o xtrace

function find_sha() {
  local MODULE=$1
  local SOURCE_JSON
  # The lockfile includes both the public and Envoy-specific registry URLs.
  SOURCE_JSON=$(jq -er --arg module "$MODULE" '
    .registryFileHashes | to_entries[] |
    select(.value != "not found") |
    .key | select(contains("/modules/" + $module + "/") and endswith("/source.json"))
  ' <<< "$LOCKFILE")
  curl -fsSL "$SOURCE_JSON" | jq -er '.url' |
    sed -E 's@.*/archive/(refs/tags/)?@@; s@\.(tar\.gz|zip)$@@; s@^v@@'
}

function find_envoy_sha_from_tag() {
  local TAG=$1
  curl -fsSL "https://api.github.com/repos/envoyproxy/envoy/commits/$TAG" | awk -F'"' '/"sha"/ && !found {print $4; found=1}'
}

CURRENT_ENVOY_RELEASE=$(cat envoy_release)
ENVOY_VERSION=$(find_envoy_sha_from_tag "$1")

LOCKFILE=$(curl -fsSL "https://raw.githubusercontent.com/envoyproxy/envoy/$ENVOY_VERSION/MODULE.bazel.lock")

GOOGLEAPIS_SHA=$(find_sha googleapis)
PGV_GIT_SHA=$(find_sha protoc-gen-validate)
PROMETHEUS_SHA=$(find_sha prometheus-metrics-model)
XDS_SHA=$(find_sha xds)
OPENTELEMETRY_SHA=$(find_sha opentelemetry-proto)
CEL_SHA=$(find_sha cel-spec)

echo -n "# Update the versions here and run update-api.sh

# envoy (source: SHA from https://github.com/envoyproxy/envoy)
ENVOY_SHA=\"$ENVOY_VERSION\"

# dependencies (source: https://github.com/envoyproxy/envoy/blob/$ENVOY_VERSION/MODULE.bazel.lock)
GOOGLEAPIS_SHA=\"$GOOGLEAPIS_SHA\"
PGV_VERSION=\"$PGV_GIT_SHA\"
PROMETHEUS_SHA=\"$PROMETHEUS_SHA\"
OPENTELEMETRY_VERSION=\"$OPENTELEMETRY_SHA\"
CEL_VERSION=\"$CEL_SHA\"
XDS_SHA=\"$XDS_SHA\"
"

# replace version in EnvoyContainer.java
sed -i "s/$CURRENT_ENVOY_RELEASE/$1/g" ../server/src/test/java/io/envoyproxy/controlplane/server/EnvoyContainer.java

# update tag in envoy_release file
echo $1 > envoy_release
