# Migrating from xDS v2 to v3

To faciliate migrating from the v2 xDS APIs to v3, this repo supports both the
v2 and v3 gRPC transports, with each transport supporting type URL rewriting of
DiscoveryResponse to whatever version the client requests with
api_resource_version.

The migration requires care - for example, using v3-only fields too soon or trying to use
deprecated v2 fields too late can cause Envoy to reject or improperly apply config.

### Recommended Sequence

This section assumes you have sufficient control over Envoy sidecar versions that you do
not need to run v2 and v3 simultaneously for a long migration period.

1. Make sure your oldest Envoy client supports final v2 message versions.
2.
    1. Ensure your control plane is not using any deprecated v2 fields.
    Deprecated v2 fields will cause errors when they are translated to v3
    (because deprecated v2 fields are dropped in v3).
    2. Configure a V3DiscoveryServer alongside the V2DiscoveryServer in your
    control plane.  You can (and should) use the same (v2) Cache implementation
    in both servers.
3. Deploy all Envoy clients to switch to both the v3 transport_api_version and
   resource_api_version in each respective xDS configs. As this happens, the V3DiscoveryServer
   will be translating your v2 resources to v3 automatically, and the V2DiscoveryServer will
   stop being used.
4.
    1. Rewrite your control plane code to use v3 resources, which means using
    V3SimpleCache (if you use SimpleCache). You may now start using v3-only
    message fields if you choose.
    2. Drop the V2DiscoveryServer.

### Alternative

Another possible path to the one above is to switch to generating v3 in the
control plane first (e.g. by using V3SimpleCache) and then deploying Envoy
clients to use v3 transport and resource versions.

This approach requires care to not use new V3-only fields until the client side
upgrade is complete (or at least understand the consequences of doing so).
