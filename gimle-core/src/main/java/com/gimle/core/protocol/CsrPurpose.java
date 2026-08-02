package com.gimle.core.protocol;

/**
 * What a certificate signing request submitted to {@code POST /bootstrap/csr} is for -- Gimlé's
 * own, simpler equivalent of Kubernetes' {@code kubernetes.io/kube-apiserver-client-kubelet} vs.
 * {@code kubernetes.io/kube-apiserver-client} {@code signerName} distinction. This is what the
 * control plane's approval policy actually switches on: a {@link #NODE_CLIENT} request backed by a
 * currently valid bootstrap token is auto-approved; an {@link #OPERATOR_CLIENT} request never is,
 * regardless of token, and sits pending until an existing operator explicitly approves it.
 */
public enum CsrPurpose {
  NODE_CLIENT,
  OPERATOR_CLIENT
}
