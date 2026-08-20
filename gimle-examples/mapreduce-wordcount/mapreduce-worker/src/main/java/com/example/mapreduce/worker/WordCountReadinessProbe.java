package com.example.mapreduce.worker;

import com.gimle.module.probe.ReadinessProbe;

/** Ready only once {@link WordCountMapWorkerHooks#onStart} has actually registered the service. */
public final class WordCountReadinessProbe implements ReadinessProbe {

  @Override
  public boolean isReady() {
    return WordCountMapWorkerHooks.ready.get();
  }
}
