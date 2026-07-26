package com.gimle.core.module;

public record ServiceExport(String interfaceName, Version version) {

  public ServiceExport {
    if (interfaceName == null || interfaceName.isBlank()) {
      throw new IllegalArgumentException("interface name must not be blank");
    }
    if (version == null) {
      throw new IllegalArgumentException("version must not be null");
    }
  }
}
