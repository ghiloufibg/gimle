package com.gimle.hilmir.release;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.hilmir.HilmirException;
import java.net.http.HttpHeaders;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ControlPlaneApiTest {

  private static final String PLAINTEXT_LIMIT_BODY =
      "plaintext mode has no caller identity to distinguish tenants -- only one real tenant may"
          + " exist at a time; use mTLS for real multi-tenancy";

  private final ControlPlaneApi api = new ControlPlaneApi("localhost:0", Optional.empty());

  @Test
  void a_plaintext_single_tenant_403_does_not_blame_the_hilmir_bookkeeping_tenant() {
    ApiResponse response =
        new ApiResponse(403, PLAINTEXT_LIMIT_BODY, HttpHeaders.of(Map.of(), (a, b) -> true));

    HilmirException thrown = assertThrows(HilmirException.class, () -> api.expectSuccess(response));

    // gimle-hilmir's own bookkeeping tenant is platform-seeded (Tenant#isPlatformSeeded) and is
    // therefore exempt from this limit -- it can never itself be the tenant that trips it, so the
    // message must not point an operator at it as the cause.
    assertFalse(
        thrown.getMessage().contains("every hilmir release verb records itself"),
        "message must not blame hilmir's own bookkeeping tenant for a limit it is exempt from: "
            + thrown.getMessage());
    assertTrue(
        thrown.getMessage().contains(PLAINTEXT_LIMIT_BODY),
        "message must still relay the control plane's own body: " + thrown.getMessage());
  }
}
