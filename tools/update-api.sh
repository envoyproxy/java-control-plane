#!/usr/bin/env bash

set -o errexit
set -o pipefail
set -o nounset

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

curl -sL https://github.com/envoyproxy/envoy/archive/${ENVOY_SHA}.tar.gz | tar xz --include="*.proto"
mkdir -p "${protodir}/envoy"
cp -r envoy-*/api/envoy/* "${protodir}/envoy"

curl -sL https://github.com/gogo/protobuf/archive/${GOGOPROTO_SHA}.tar.gz | tar xz --include="*.proto"
mkdir -p "${protodir}/gogoproto"
cp protobuf-*/gogoproto/gogo.proto "${protodir}/gogoproto"

curl -sL https://github.com/googleapis/googleapis/archive/${GOOGLEAPIS_SHA}.tar.gz | tar xz --include="*.proto"
mkdir -p "${protodir}/google/api"
mkdir -p "${protodir}/google/rpc"
cp googleapis-*/google/api/annotations.proto googleapis-*/google/api/http.proto "${protodir}/google/api"
cp googleapis-*/google/rpc/status.proto "${protodir}/google/rpc"

curl -sL https://github.com/lyft/protoc-gen-validate/archive/${PGV_GIT_SHA}.tar.gz | tar xz --include="*.proto"
mkdir -p "${protodir}/validate"
cp -r protoc-gen-validate-*/validate/* "${protodir}/validate"

curl -sL https://github.com/census-instrumentation/opencensus-proto/archive/${OPENCENSUS_SHA}.tar.gz | tar xz --include="*.proto"
cp opencensus-proto-*/src/opencensus/proto/trace/v1/trace.proto "${protodir}"

curl -sL https://github.com/prometheus/client_model/archive/${PROMETHEUS_SHA}.tar.gz | tar xz --include="*.proto"
cp client_model-*/metrics.proto "${protodir}"

popd >/dev/null
