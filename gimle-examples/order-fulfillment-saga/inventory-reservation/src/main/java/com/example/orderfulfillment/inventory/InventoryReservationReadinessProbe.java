package com.example.orderfulfillment.inventory;

import com.gimle.module.probe.ReadinessProbe;

/** Ready only once {@link InventoryReservationHooks#onStart} has registered the service. */
public final class InventoryReservationReadinessProbe implements ReadinessProbe {

  @Override
  public boolean isReady() {
    return InventoryReservationHooks.ready.get();
  }
}
