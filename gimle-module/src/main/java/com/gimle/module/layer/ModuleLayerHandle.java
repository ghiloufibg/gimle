package com.gimle.module.layer;

/** The live {@link ModuleLayer}/{@link ClassLoader} pair backing a resolved module. */
public record ModuleLayerHandle(ModuleLayer layer, ClassLoader loader) {

  public ModuleLayerHandle {
    if (layer == null) {
      throw new IllegalArgumentException("layer must not be null");
    }
    if (loader == null) {
      throw new IllegalArgumentException("loader must not be null");
    }
  }
}
