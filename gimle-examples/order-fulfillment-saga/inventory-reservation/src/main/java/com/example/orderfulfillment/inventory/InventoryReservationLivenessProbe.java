package com.example.orderfulfillment.inventory;

import com.gimle.module.probe.LivenessProbe;

/** This module has no failure mode of its own to report -- always alive once loaded. */
public final class InventoryReservationLivenessProbe implements LivenessProbe {

  @Override
  public boolean isAlive() {
    return true;
  }
}
