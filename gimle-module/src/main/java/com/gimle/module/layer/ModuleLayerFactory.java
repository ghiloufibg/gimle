package com.gimle.module.layer;

import com.gimle.core.exception.GimleResolutionException;
import com.gimle.core.module.ModuleInstanceId;
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
 *
 * <p>The new module is also granted readability to {@code parentLoader}'s own unnamed module.
 * {@code gimle-worker} (and every gimle-* jar) runs unnamed itself (launched via {@code -cp}, not
 * {@code --module-path} — {@code gimle-api} doesn't exist yet to be a real platform module on the
 * boot layer, see {@code PlatformLayer}), so {@code com.gimle.module.lifecycle.*}/{@code
 * com.gimle.module.probe.*}/{@code org.slf4j.*} are themselves unnamed-module classes today. A
 * hosted module that bundles a real {@code ModuleLifecycleHooks}/probe implementation in its own
 * jar declares those as {@code requires static} (so {@code Configuration.resolve} succeeds even
 * though the platform layer doesn't carry them as named modules) and needs this {@code addReads} to
 * actually load and correctly cast to the platform's own copy of those types at runtime — without
 * it, the hosted module's classloader can't see them at all, or (if given a second, freshly-defined
 * copy of {@code com.gimle.module} instead) casts against a different {@code Class} instance than
 * the one the rest of the runtime uses. {@code Module#addReads} requires the caller to *be* the
 * module being granted access; {@link ModuleLayer.Controller} is the sanctioned third-party API for
 * this factory, as the layer's own creator, to grant it instead.
 */
public final class ModuleLayerFactory {

  private ModuleLayerFactory() {}

  public static ModuleLayerHandle create(
      ModuleInstanceId id, Path jarPath, List<ModuleLayer> parentLayers, ClassLoader parentLoader) {
    try {
      ModuleFinder finder = ModuleFinder.of(jarPath);
      List<Configuration> parentConfigurations =
          parentLayers.stream().map(ModuleLayer::configuration).toList();
      Configuration cf =
          Configuration.resolve(
              finder, parentConfigurations, ModuleFinder.of(), List.of(id.name()));
      ModuleLayer.Controller controller =
          ModuleLayer.defineModulesWithOneLoader(cf, parentLayers, parentLoader);
      ModuleLayer layer = controller.layer();
      Module hostedModule = layer.findModule(id.name()).orElseThrow();
      controller.addReads(hostedModule, parentLoader.getUnnamedModule());
      ClassLoader loader = layer.findLoader(id.name());
      return new ModuleLayerHandle(layer, loader);
    } catch (FindException | ResolutionException | LayerInstantiationException e) {
      throw GimleResolutionException.layerInstantiationFailed(id, e);
    }
  }
}
