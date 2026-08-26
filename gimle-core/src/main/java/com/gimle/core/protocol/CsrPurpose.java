package com.gimle.core.protocol;

/**
 * What a certificate signing request submitted to {@code POST /bootstrap/csr} is for -- Gimlé's
 * own, simpler equivalent of Kubernetes' {@code kubernetes.io/kube-apiserver-client-kubelet} vs.
 * {@code kubernetes.io/kube-apiserver-client} {@code signerName} distinction. This is what the
 * control plane's approval policy actually switches on: a {@link #NODE_CLIENT} request backed by a
 * currently valid bootstrap token is auto-approved; an {@link #OPERATOR_CLIENT} request never is,
 * regardless of token, and sits pending until an existing operator explicitly approves it; a {@link
 * #TENANT_CLIENT} request is signed immediately but only for a caller already authorized to approve
 * certificate requests (an operator minting a tenant-membership client certificate, {@code
 * O=gimle:tenant:<id>}, for one of that tenant's own callers -- what a TLS-terminating proxy
 * verifies a caller's tenant against).
 */
public enum CsrPurpose {
  NODE_CLIENT,
  OPERATOR_CLIENT,
  TENANT_CLIENT
}
