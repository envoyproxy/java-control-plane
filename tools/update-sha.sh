#!/usr/bin/env bash

function find_sha() {
  local CONTENT=$1
  local DEPENDENCY=$2
  echo "$CONTENT" | grep "$DEPENDENCY" -A 11 | grep -m 1 version | awk '{ print $3 }' | tr -d '"' | tr -d ","
}

function find_date() {
  local CONTENT=$1
  local DEPENDENCY=$2
  echo "$CONTENT" | grep "$DEPENDENCY" -A 11 | grep -m 1 release_date | awk '{ print $3 }' | tr -d '"' | tr -d ","
}

function find_envoy_sha_from_tag() {
  local TAG=$1
  curl -s https://api.github.com/repos/envoyproxy/envoy/tags | grep "$TAG" -A 4 | grep sha | awk '{print $2}' | tr -d '"' | tr -d ","
}

ENVOY_VERSION=$(find_envoy_sha_from_tag "$1")

CURL_OUTPUT=$(curl -s "https://raw.githubusercontent.com/envoyproxy/envoy/$ENVOY_VERSION/api/bazel/repository_locations.bzl")

GOOGLEAPIS_SHA=$(find_sha "$CURL_OUTPUT" com_google_googleapis)
GOOGLEAPIS_DATE=$(find_date "$CURL_OUTPUT" com_google_googleapis)

PGV_GIT_SHA=$(find_sha "$CURL_OUTPUT" com_envoyproxy_protoc_gen_validate)
PGV_GIT_DATE=$(find_date "$CURL_OUTPUT" com_envoyproxy_protoc_gen_validate)

PROMETHEUS_SHA=$(find_sha "$CURL_OUTPUT" prometheus_metrics_model)
PROMETHEUS_DATE=$(find_date "$CURL_OUTPUT" prometheus_metrics_model)

OPENCENSUS_SHA=$(find_sha "$CURL_OUTPUT" opencensus_proto)
OPENCENSUS_DATE=$(find_date "$CURL_OUTPUT" opencensus_proto)

UDPA_SHA=$(find_sha "$CURL_OUTPUT" com_github_cncf_udpa)
UDPA_DATE=$(find_date "$CURL_OUTPUT" com_github_cncf_udpa)

OPETELEMETRY_SHA=$(find_sha "$CURL_OUTPUT" opentelemetry_proto)
OPETELEMETRY_DATE=$(find_date "$CURL_OUTPUT" opentelemetry_proto)

echo -n "# Update the versions here and run update-api.sh

# envoy (source: SHA from https://github.com/envoyproxy/envoy)
ENVOY_SHA=\"$ENVOY_VERSION\"

# dependencies (source: https://github.com/envoyproxy/envoy/blob/$ENVOY_VERSION/api/bazel/repository_locations.bzl)
GOOGLEAPIS_SHA=\"$GOOGLEAPIS_SHA\"  # $GOOGLEAPIS_DATE
PGV_VERSION=\"$PGV_GIT_SHA\"  # $PGV_GIT_DATE
PROMETHEUS_SHA=\"$PROMETHEUS_SHA\"  # $PROMETHEUS_DATE
OPENCENSUS_VERSION=\"$OPENCENSUS_SHA\"  # $OPENCENSUS_DATE
OPETELEMETRY_VERSION=\"$OPETELEMETRY_SHA\"  # $OPETELEMETRY_DATE
UDPA_SHA=\"$UDPA_SHA\"  # $UDPA_DATE
"
