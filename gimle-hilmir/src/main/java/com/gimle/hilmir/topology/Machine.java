package com.gimle.hilmir.topology;

import com.gimle.core.exception.GimleManifestException;

/** One physical or virtual machine a topology can place processes on: its name and its host. */
public record Machine(String name, String host) {

  public Machine {
    if (name == null || name.isBlank()) {
      throw new GimleManifestException("machine name must be a non-blank string");
    }
    if (host == null || host.isBlank()) {
      throw new GimleManifestException("machine " + name + " must declare a non-blank host");
    }
  }
}
