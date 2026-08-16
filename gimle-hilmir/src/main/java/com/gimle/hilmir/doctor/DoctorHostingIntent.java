package com.gimle.hilmir.doctor;

/**
 * Which of the platform's two hosting modes {@code doctor} should evaluate the artifact under. The
 * platform itself decides vessel-vs-module purely from a deploy manifest's own {@code vessel:}
 * block presence/absence, never by sniffing the jar -- {@code doctor} mirrors that same posture by
 * requiring the caller to state its intent ({@code --vessel} to ask for {@link #VESSEL}) rather
 * than guessing, and defaults to {@link #MODULE} since that is the richer, more constrained path
 * (real isolation-tier/resource/probe/hook validation) most callers evaluating a freshly built jar
 * want.
 */
public enum DoctorHostingIntent {
  MODULE,
  VESSEL
}
