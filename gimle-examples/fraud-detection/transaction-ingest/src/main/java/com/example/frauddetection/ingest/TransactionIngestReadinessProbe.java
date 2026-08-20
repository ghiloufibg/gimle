package com.example.frauddetection.ingest;

import com.gimle.module.probe.ReadinessProbe;

/** Ready once at least one transaction has been successfully scored. */
public final class TransactionIngestReadinessProbe implements ReadinessProbe {

  @Override
  public boolean isReady() {
    return TransactionIngestHooks.everSucceeded.get();
  }
}
