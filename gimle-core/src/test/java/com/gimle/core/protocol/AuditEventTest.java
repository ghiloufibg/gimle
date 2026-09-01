package com.gimle.core.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AuditEventTest {

  private static AuditEvent fullEvent() {
    return new AuditEvent(
        "evt-1",
        "alice",
        Set.of("gimle:operators"),
        "DEPLOYMENT",
        "WRITE",
        Optional.of("asgard"),
        Optional.of("orders-service"),
        true,
        1_000L);
  }

  @Test
  void every_field_round_trips_through_the_accessors() {
    AuditEvent event = fullEvent();
    assertEquals("evt-1", event.id());
    assertEquals("alice", event.principal());
    assertEquals(Set.of("gimle:operators"), event.groups());
    assertEquals("DEPLOYMENT", event.resourceKind());
    assertEquals("WRITE", event.verb());
    assertEquals(Optional.of("asgard"), event.tenantId());
    assertEquals(Optional.of("orders-service"), event.targetId());
    assertTrue(event.allowed());
    assertEquals(1_000L, event.occurredAtEpochMilli());
  }

  @Test
  void a_denied_decision_is_represented_the_same_way_as_an_allowed_one() {
    AuditEvent denied =
        new AuditEvent(
            "evt-2",
            "mallory",
            Set.of(),
            "SECRET",
            "DELETE",
            Optional.empty(),
            Optional.empty(),
            false,
            2_000L);
    assertFalse(denied.allowed());
  }

  @Test
  void null_groups_tenant_and_target_coalesce_to_empty_rather_than_null() {
    AuditEvent event =
        new AuditEvent("evt-3", "bob", null, "TENANT", "DELETE", null, null, false, 3_000L);
    assertEquals(Set.of(), event.groups());
    assertEquals(Optional.empty(), event.tenantId());
    assertEquals(Optional.empty(), event.targetId());
  }

  @Test
  void a_versioned_write_carries_the_version_it_produced() {
    AuditEvent event =
        new AuditEvent(
            "evt-4",
            "alice",
            Set.of(),
            "SECRET",
            "WRITE",
            Optional.of("asgard"),
            Optional.of("db-password"),
            true,
            AuditOutcome.APPLIED,
            4_000L,
            OptionalInt.of(3));
    assertEquals(OptionalInt.of(3), event.version());
  }

  @Test
  void an_event_for_an_unversioned_resource_kind_carries_no_version() {
    assertEquals(OptionalInt.empty(), fullEvent().version());
  }

  @Test
  void a_null_version_coalesces_to_empty_rather_than_null() {
    AuditEvent event =
        new AuditEvent(
            "evt-5",
            "alice",
            Set.of(),
            "SECRET",
            "READ",
            Optional.empty(),
            Optional.empty(),
            true,
            AuditOutcome.APPLIED,
            5_000L,
            null);
    assertEquals(OptionalInt.empty(), event.version());
  }

  @Test
  void a_blank_id_is_rejected() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AuditEvent(
                " ",
                "alice",
                Set.of(),
                "DEPLOYMENT",
                "WRITE",
                Optional.empty(),
                Optional.empty(),
                true,
                1L));
  }

  @Test
  void a_blank_principal_is_rejected() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AuditEvent(
                "evt-1",
                "",
                Set.of(),
                "DEPLOYMENT",
                "WRITE",
                Optional.empty(),
                Optional.empty(),
                true,
                1L));
  }

  @Test
  void a_blank_resource_kind_is_rejected() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AuditEvent(
                "evt-1",
                "alice",
                Set.of(),
                " ",
                "WRITE",
                Optional.empty(),
                Optional.empty(),
                true,
                1L));
  }

  @Test
  void a_blank_verb_is_rejected() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AuditEvent(
                "evt-1",
                "alice",
                Set.of(),
                "DEPLOYMENT",
                "",
                Optional.empty(),
                Optional.empty(),
                true,
                1L));
  }
}
