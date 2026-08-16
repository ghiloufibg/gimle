package com.gimle.hilmir.doctor;

/**
 * One diagnostic outcome from {@link DoctorAnalyzer}, the same {@code (code, severity, message)}
 * shape {@code TopologyValidator}'s own {@code Finding} already established. {@code code} is the
 * stable, grepped-and-scripted-against contract -- never renamed once shipped, only added to.
 */
public record DoctorFinding(String code, DoctorSeverity severity, String message) {}
