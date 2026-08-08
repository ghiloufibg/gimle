package com.gimle.core.protocol;

/**
 * The kind of lifecycle transition an {@link InstanceEvent} records -- one value per {@code
 * gimle-module}'s own {@code LifecycleEvent} sealed-interface variant. Deliberately its own copy
 * rather than a shared reference to that type: {@code gimle-core} has no dependency on {@code
 * gimle-module}, the same reason {@link ControlMessage.ModuleStateChanged#state} already travels as
 * a plain {@code String} instead of {@code gimle-module}'s own state enum.
 */
public enum InstanceEventKind {
  INSTALLED,
  RESOLVED,
  STARTING,
  ACTIVE,
  STOPPING,
  UNINSTALLED,
  TRANSITION_FAILED
}
