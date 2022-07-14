# java-control-plane

[![CircleCI](https://circleci.com/gh/envoyproxy/java-control-plane.svg?style=svg)](https://circleci.com/gh/envoyproxy/java-control-plane) [![codecov](https://codecov.io/gh/envoyproxy/java-control-plane/branch/main/graph/badge.svg)](https://codecov.io/gh/envoyproxy/java-control-plane) [![Maven Central](https://maven-badges.herokuapp.com/maven-central/io.envoyproxy.controlplane/java-control-plane/badge.svg)](https://maven-badges.herokuapp.com/maven-central/io.envoyproxy.controlplane/java-control-plane)

This repository contains a Java-based implementation of an API server that implements the discovery service APIs defined
in [data-plane-api](https://github.com/envoyproxy/data-plane-api). It started life as a port of
[go-control-plane](https://github.com/envoyproxy/go-control-plane), but building an idiomatic Java implementation is
prioritized over exact interface parity with the Go implementation.

Only v3 resources as well as transport versions are now supported. Migrating
to v3 is necessary as Envoy dropped v2 support at EOY 2020 (see
[API_VERSIONING.md](https://github.com/envoyproxy/envoy/blob/4c6206865061591155d18b55972b4d626e1703dd/api/API_VERSIONING.md))

See the [v2-to-v3 migration guide](https://github.com/envoyproxy/java-control-plane/blob/main/V2_TO_V3_GUIDE.md) for an explanation of migration paths.

### Requirements

1. Java 8+
2. Maven

### Build & Test

```bash
mvn clean package
```

More thorough usage examples are still TODO, but there is a basic test implementation in
[TestMain](server/src/test/java/io/envoyproxy/controlplane/server/TestMain.java).

### Envoy API
There is automation built into this repo to keep the Envoy API (i.e. protobuf files)
up to date with the latest available Envoy release.

This automation uses Github Workflows and works as follows: a scheduled workflow runs with
weekly cadence, and calls a reusable workflow that fetches the latest available Envoy release
and compares it with the version currently used in the repo.

If the latest available Envoy release doesn't match the version currently used, another
reusable workflow is called which creates a PR that upgrades the Envoy API to the latest
available release.

### Releasing a new version
To release and publish a new version, do the following:
1. create a personal API token in CircleCI by following the instructions listed [here](https://circleci.com/docs/2.0/managing-api-tokens/#creating-a-personal-api-token)
2. from terminal, curl the CircleCI API as follows

```
 curl -X POST \
    -H "Content-Type: application/json" \
    -d '{ "build_parameters": { "RELEASE": "<e.g. 0.1.29>", "NEXT": "<e.g. 0.1.30-SNAPSHOT>" } }' \
    "https://circleci.com/api/v1.1/project/github/envoyproxy/java-control-plane/tree/main?circle-token=<API token>"
```
