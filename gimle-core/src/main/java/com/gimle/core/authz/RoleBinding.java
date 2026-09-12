package com.gimle.core.authz;

/**
 * Grants every {@link Permission} in {@code roleName}'s {@link Role} to {@code subject} -- {@code
 * "user:<principal-name>"} or {@code "group:<group-name>"}, a plain prefixed string rather than a
 * small {@code Subject} type hierarchy (kind + name), matching this codebase's existing preference
 * for a simple string identifier where a hierarchy buys nothing (see {@code tenantId} elsewhere).
 * Resolution is additive-only across every binding matching a given {@link Principal}: a
 * principal's effective permissions are the union of every matching binding's role, never
 * subtractive.
 */
public record RoleBinding(String id, String subject, String roleName) {

  private static final String USER_PREFIX = "user:";
  private static final String GROUP_PREFIX = "group:";

  public RoleBinding {
    // Trimmed before storing, not just before validating: subject is matched for exact string
    // equality against Authorizer's own RoleBinding.userSubject/groupSubject output, and roleName
    // against a stored Role's own name -- a binding built from an API body carrying incidental
    // whitespace (a copy-pasted CLI argument, a trailing newline) would otherwise never match
    // either lookup and silently grant nothing, with no error to say why.
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("id must not be blank");
    }
    id = id.trim();
    if (subject != null) {
      subject = subject.trim();
    }
    if (subject == null || !(subject.startsWith(USER_PREFIX) || subject.startsWith(GROUP_PREFIX))) {
      throw new IllegalArgumentException(
          "subject must start with \""
              + USER_PREFIX
              + "\" or \""
              + GROUP_PREFIX
              + "\": "
              + subject);
    }
    if (roleName == null || roleName.isBlank()) {
      throw new IllegalArgumentException("roleName must not be blank");
    }
    roleName = roleName.trim();
  }

  public static String userSubject(String principalName) {
    return USER_PREFIX + principalName;
  }

  public static String groupSubject(String groupName) {
    return GROUP_PREFIX + groupName;
  }
}
