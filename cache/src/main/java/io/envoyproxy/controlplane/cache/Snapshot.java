package io.envoyproxy.controlplane.cache;

import com.google.protobuf.Message;
import io.envoyproxy.controlplane.cache.Resources.ResourceType;
import java.util.List;
import java.util.Map;
import java.util.Set;

public abstract class Snapshot {

  abstract String version(ResourceType resourceType, List<String> resourceNames);

  abstract Map<String, ? extends Message> resources(ResourceType resourceType);

  /**
   * Asserts that all of the given resource names have corresponding values in the given resources collection.
   *
   * @param parentTypeUrl the type of the parent resources (source of the resource name refs)
   * @param dependencyTypeUrl the type of the given dependent resources
   * @param resourceNames the set of dependent resource names that must exist
   * @param resources the collection of resources whose names are being checked
   * @throws SnapshotConsistencyException if a name is given that does not exist in the resources collection
   */
  protected static void ensureAllResourceNamesExist(
      String parentTypeUrl,
      String dependencyTypeUrl,
      Set<String> resourceNames,
      Map<String, ? extends Message> resources) throws SnapshotConsistencyException {

    if (resourceNames.size() != resources.size()) {
      throw new SnapshotConsistencyException(
          String.format(
              "Mismatched %s -> %s reference and resource lengths, [%s] != %d",
              parentTypeUrl,
              dependencyTypeUrl,
              String.join(", ", resourceNames),
              resources.size()));
    }

    for (String name : resourceNames) {
      if (!resources.containsKey(name)) {
        throw new SnapshotConsistencyException(
            String.format(
                "%s named '%s', referenced by a %s, not listed in [%s]",
                dependencyTypeUrl,
                name,
                parentTypeUrl,
                String.join(", ", resources.keySet())));
      }
    }
  }
}
