package com.gimle.agent.bifrost;

/** One live backend a service's traffic can be forwarded to: a reachable host and port. */
public record ServiceEndpoint(String host, int port) {}
