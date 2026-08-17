package com.example.inventory.provider;

import com.gimle.module.probe.LivenessProbe;

/** No failure mode of its own -- same reasoning as orders-service's own liveness probe. */
public final class InventoryLivenessProbe implements LivenessProbe {

  @Override
  public boolean isAlive() {
    return true;
  }
}
