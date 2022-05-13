#!/usr/bin/env bash

set -o errexit
set -o pipefail
set -o xtrace

__dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

source "${__dir}/API_SHAS"

protodir="${__dir}/../api/src/main/proto"
tmpdir=`mktemp -d 2>/dev/null || mktemp -d -t 'tmpdir'`

# Check if the temp dir was created.
if [[ ! "${tmpdir}" || ! -d "${tmpdir}" ]]; then
  echo "Could not create temp dir"
  exit 1
fi

# Clean up the temp directory that we created.
function cleanup {
  rm -rf "${tmpdir}"
}

# Register the cleanup function to be called on the EXIT signal.
trap cleanup EXIT

pushd "${tmpdir}" >/dev/null

rm -rf "${protodir}"

curl -sL https://github.com/envoyproxy/envoy/archive/${ENVOY_SHA}.tar.gz | tar xz --wildcards '*.proto'
mkdir -p "${protodir}/envoy"
cp -r envoy-*/api/envoy/* "${protodir}/envoy"

curl -sL https://github.com/googleapis/googleapis/archive/${GOOGLEAPIS_SHA}.tar.gz | tar xz --wildcards '*.proto'
mkdir -p "${protodir}/google/api"
mkdir -p "${protodir}/google/api/expr/v1alpha1"
mkdir -p "${protodir}/google/rpc"
cp googleapis-*/google/api/annotations.proto googleapis-*/google/api/http.proto "${protodir}/google/api"
cp googleapis-*/google/api/expr/v1alpha1/syntax.proto "${protodir}/google/api/expr/v1alpha1"
cp googleapis-*/google/api/expr/v1alpha1/checked.proto "${protodir}/google/api/expr/v1alpha1"
cp googleapis-*/google/rpc/status.proto "${protodir}/google/rpc"

curl -sL https://github.com/envoyproxy/protoc-gen-validate/archive/v${PGV_VERSION}.tar.gz | tar xz --wildcards '*.proto'
mkdir -p "${protodir}/validate"
cp -r protoc-gen-validate-*/validate/* "${protodir}/validate"

curl -sL https://github.com/prometheus/client_model/archive/${PROMETHEUS_SHA}.tar.gz | tar xz --wildcards '*.proto'
mkdir -p "${protodir}/io/prometheus/client/"
cp client_model-*/io/prometheus/client/metrics.proto "${protodir}/io/prometheus/client/"

curl -sL https://github.com/cncf/xds/archive/${UDPA_SHA}.tar.gz | tar xz --wildcards '*.proto'
mkdir -p "${protodir}/udpa"
mkdir -p "${protodir}/xds"
cp -r xds-*/udpa/* "${protodir}/udpa"
cp -r xds-*/xds/* "${protodir}/xds"

curl -sL https://github.com/open-telemetry/opentelemetry-proto/archive/v${OPENTELEMETRY_VERSION}.tar.gz | tar xz --wildcards '*.proto'
mkdir -p "${protodir}/opentelemetry/proto"
cp -r opentelemetry-proto-*/opentelemetry/proto/* "${protodir}/opentelemetry/proto"

popd >/dev/null
