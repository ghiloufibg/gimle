/**
 * See {@code greeter-provider}'s own module-info for why {@code com.gimle.module}/{@code org.slf4j}
 * are {@code requires static}, and why {@code com.gimle.examples.greeter} (not just {@code
 * .consumer}) must be exported -- {@code FabricServer} reflects into a registered service's own
 * interface package from outside its module.
 */
module com.gimle.examples.greeter.consumer {
  requires static com.gimle.module;
  requires static org.slf4j;

  exports com.gimle.examples.greeter.consumer;
  exports com.gimle.examples.greeter;
}
