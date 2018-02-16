# java-control-plane

[![CircleCI](https://circleci.com/gh/envoyproxy/java-control-plane.svg?style=svg)](https://circleci.com/gh/envoyproxy/java-control-plane) [![codecov](https://codecov.io/gh/envoyproxy/java-control-plane/branch/master/graph/badge.svg)](https://codecov.io/gh/envoyproxy/java-control-plane)

This repository contains a Java-based implementation of an API server that implements the discovery service APIs defined
in [data-plane-api](https://github.com/envoyproxy/data-plane-api). It started life as a port of
[go-control-plane](https://github.com/envoyproxy/go-control-plane), but building an idiomatic Java implementation is
prioritized over exact interface parity with the Go implementation.

### Requirements

1. Java 8+
2. Maven

### Build & Test

```bash
mvn clean package
```

More thorough usage examples are still TODO, but there is a basic test implementation in
[TestMain](server/src/test/java/io/envoyproxy/controlplane/server/TestMain.java).
