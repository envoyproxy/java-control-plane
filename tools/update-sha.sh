#!/usr/bin/env bash

set -o errexit
set -o pipefail
set -o nounset
set -o xtrace

function find_sha() {
  local CONTENT=$1
  local DEPENDENCY=$2
  echo "$CONTENT" | grep "$DEPENDENCY" -A 11 | grep -m 1 "version =" | awk '{ print $3 }' | tr -d '"' | tr -d ","
}

function find_date() {
  local CONTENT=$1
  local DEPENDENCY=$2
  echo "$CONTENT" | grep "$DEPENDENCY" -A 5 | grep -m 1 "release_date:" | awk '{ print $2 }' | tr -d '"'
}

function find_envoy_sha_from_tag() {
  local TAG=$1
  curl -s https://api.github.com/repos/envoyproxy/envoy/tags | grep "$TAG" -A 4 | grep sha | awk '{print $2}' | tr -d '"' | tr -d ","
}

CURRENT_ENVOY_RELEASE=$(cat envoy_release)
ENVOY_VERSION=$(find_envoy_sha_from_tag "$1")

LOCATIONS=$(curl -s "https://raw.githubusercontent.com/envoyproxy/envoy/$ENVOY_VERSION/api/bazel/repository_locations.bzl")
DEPS_YAML=$(curl -s "https://raw.githubusercontent.com/envoyproxy/envoy/$ENVOY_VERSION/api/bazel/deps.yaml")

GOOGLEAPIS_SHA=$(find_sha "$LOCATIONS" com_google_googleapis)
GOOGLEAPIS_DATE=$(find_date "$DEPS_YAML" com_google_googleapis)

PGV_GIT_SHA=$(find_sha "$LOCATIONS" com_envoyproxy_protoc_gen_validate)
PGV_GIT_DATE=$(find_date "$DEPS_YAML" com_envoyproxy_protoc_gen_validate)

PROMETHEUS_SHA=$(find_sha "$LOCATIONS" prometheus_metrics_model)
PROMETHEUS_DATE=$(find_date "$DEPS_YAML" prometheus_metrics_model)

# renamed from com_github_cncf_xds to xds in envoy 1.38+
XDS_SHA=$(find_sha "$LOCATIONS" "xds = dict")
XDS_DATE=$(find_date "$DEPS_YAML" "^xds:")

OPENTELEMETRY_SHA=$(find_sha "$LOCATIONS" opentelemetry_proto)
OPENTELEMETRY_DATE=$(find_date "$DEPS_YAML" opentelemetry_proto)

CEL_SHA=$(find_sha "$LOCATIONS" dev_cel)
CEL_DATE=$(find_date "$DEPS_YAML" dev_cel)

echo -n "# Update the versions here and run update-api.sh

# envoy (source: SHA from https://github.com/envoyproxy/envoy)
ENVOY_SHA=\"$ENVOY_VERSION\"

# dependencies (source: https://github.com/envoyproxy/envoy/blob/$ENVOY_VERSION/api/bazel/repository_locations.bzl)
GOOGLEAPIS_SHA=\"$GOOGLEAPIS_SHA\"  # $GOOGLEAPIS_DATE
PGV_VERSION=\"$PGV_GIT_SHA\"  # $PGV_GIT_DATE
PROMETHEUS_SHA=\"$PROMETHEUS_SHA\"  # $PROMETHEUS_DATE
OPENTELEMETRY_VERSION=\"$OPENTELEMETRY_SHA\"  # $OPENTELEMETRY_DATE
CEL_VERSION=\"$CEL_SHA\"  # $CEL_DATE
XDS_SHA=\"$XDS_SHA\"  # $XDS_DATE
"

# replace version in EnvoyContainer.java
sed -i "s/$CURRENT_ENVOY_RELEASE/$1/g" ../server/src/test/java/io/envoyproxy/controlplane/server/EnvoyContainer.java

# update tag in envoy_release file
echo $1 > envoy_release
