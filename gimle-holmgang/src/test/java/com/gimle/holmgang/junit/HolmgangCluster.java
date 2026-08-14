package com.gimle.holmgang.junit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the field of a {@link Holmgang}-annotated test class that receives the running {@link
 * com.gimle.holmgang.cluster.GimleCluster} before each test.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface HolmgangCluster {}
