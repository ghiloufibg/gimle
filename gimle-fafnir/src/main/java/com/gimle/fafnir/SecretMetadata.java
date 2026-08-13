package com.gimle.fafnir;

/**
 * One row of {@code GET /secrets/{tenantId}}'s list response -- metadata only, never a {@code
 * value}, matching Vault's own {@code kv list} / Kubernetes' {@code list} RBAC verb being distinct
 * from {@code get}. Derived from a secret's {@code key@meta} pointer entry; {@code key} here is the
 * logical secret name with the {@code @meta} suffix already stripped back off.
 */
public record SecretMetadata(String key, int latestVersion, boolean deleted) {}
