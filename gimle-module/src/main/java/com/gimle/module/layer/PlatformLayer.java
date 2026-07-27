package com.gimle.module.layer;

import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.nio.file.Path;
import java.util.List;

/**
 * The shared parent layer every module layer parents on. In the real worker runtime this is built
 * once per worker JVM from the boot layer plus gimle-core and gimle-api on the module path — but
 * gimle-api doesn't exist yet, so this stays generic over an arbitrary set of additional
 * module-path entries rather than hard-coding that dependency. Tests that don't need the platform
 * API can use {@link #bootOnly()}.
 */
public final class PlatformLayer {

  private final ModuleLayer layer;

  private PlatformLayer(ModuleLayer layer) {
    this.layer = layer;
  }

  public static PlatformLayer bootOnly() {
    return new PlatformLayer(ModuleLayer.boot());
  }

  public static PlatformLayer withAdditionalModules(
      List<Path> modulePathEntries, List<String> rootModules) {
    ModuleFinder finder = ModuleFinder.of(modulePathEntries.toArray(Path[]::new));
    Configuration cf =
        Configuration.resolve(
            finder, List.of(ModuleLayer.boot().configuration()), ModuleFinder.of(), rootModules);
    ModuleLayer layer =
        ModuleLayer.boot().defineModulesWithOneLoader(cf, ClassLoader.getSystemClassLoader());
    return new PlatformLayer(layer);
  }

  public ModuleLayer layer() {
    return layer;
  }
}
