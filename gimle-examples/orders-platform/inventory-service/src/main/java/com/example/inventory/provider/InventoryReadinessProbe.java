package com.example.inventory.provider;

import com.gimle.module.probe.ReadinessProbe;

/** Ready once {@link InventoryServiceHooks#onStart} has published {@link StockLedger} on the
 * fabric. */
public final class InventoryReadinessProbe implements ReadinessProbe {

  @Override
  public boolean isReady() {
    return InventoryServiceHooks.ready.get();
  }
}
