package com.example.orders.provider;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Explicit {@code @Bean} factory methods, no {@code @ComponentScan} -- classpath scanning pulls
 * in Spring's classgraph/ASM-based reflection machinery for no benefit here (two beans, known
 * ahead of time), and {@code proxyBeanMethods = false} skips CGLIB-subclassing this class
 * entirely, since no {@code @Bean} method here ever calls another. Both are the same "smallest
 * real Spring surface that still does real dependency injection" choice this whole app makes.
 */
@Configuration(proxyBeanMethods = false)
public class OrdersConfiguration {

  @Bean
  public OrderIdGenerator orderIdGenerator() {
    return new OrderIdGenerator();
  }

  @Bean
  public OrderBook orderBook(OrderIdGenerator orderIdGenerator) {
    return new OrderBook(orderIdGenerator);
  }
}
