package com.gimle.module.layer;

import com.gimle.core.exception.GimleResolutionException;
import com.gimle.core.module.ModuleId;
import java.lang.module.Configuration;
import java.lang.module.FindException;
import java.lang.module.ModuleFinder;
import java.lang.module.ResolutionException;
import java.nio.file.Path;
import java.util.List;

/**
 * Builds a module's own {@link ModuleLayer}: one dedicated classloader per {@code (name, version)},
 * parented on the platform layer and every wired dependency's own layer, so the module can read
 * exactly the platform's and its dependencies' exported packages and nothing else. Split-package
 * conflicts are detected by JPMS itself during {@code Configuration.resolve} (a {@link
 * ResolutionException}) rather than by an independent pre-check — re-implementing package-overlap
 * detection would risk diverging from the JDK's own canonical algorithm for no benefit.
 */
public final class ModuleLayerFactory {

  private ModuleLayerFactory() {}

  public static ModuleLayerHandle create(
      ModuleId id, Path jarPath, List<ModuleLayer> parentLayers, ClassLoader parentLoader) {
    try {
      ModuleFinder finder = ModuleFinder.of(jarPath);
      List<Configuration> parentConfigurations =
          parentLayers.stream().map(ModuleLayer::configuration).toList();
      Configuration cf =
          Configuration.resolve(
              finder, parentConfigurations, ModuleFinder.of(), List.of(id.name()));
      ModuleLayer layer =
          ModuleLayer.defineModulesWithOneLoader(cf, parentLayers, parentLoader).layer();
      ClassLoader loader = layer.findLoader(id.name());
      return new ModuleLayerHandle(layer, loader);
    } catch (FindException | ResolutionException | LayerInstantiationException e) {
      throw GimleResolutionException.layerInstantiationFailed(id, e);
    }
  }
}
