package com.gimle.cli;

/**
 * One named control-plane endpoint in the CLI's own config file. Deliberately endpoint-only: no
 * credential ever lands here. A client certificate/key still comes from {@code gimle.tls.*}, which
 * points at files the operator manages themselves, so this file staying non-secret is a property of
 * what it can hold rather than of how carefully it is written.
 */
record CliContext(String name, String server) {}
