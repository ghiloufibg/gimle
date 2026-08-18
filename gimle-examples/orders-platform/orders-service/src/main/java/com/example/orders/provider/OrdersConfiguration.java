package com.example.orders.provider;

import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Explicit {@code @Bean} factory methods, no {@code @ComponentScan} -- classpath scanning pulls
 * in Spring's classgraph/ASM-based reflection machinery for no benefit here (two beans, known
 * ahead of time), and {@code proxyBeanMethods = false} skips CGLIB-subclassing this class
 * entirely, since no {@code @Bean} method here ever calls another. Both are the same "smallest
 * real Spring surface that still does real dependency injection" choice this whole app makes.
 *
 * <p>The {@link DataSource} bean itself is deliberately not defined here: it needs connection
 * details {@link OrdersServiceHooks#onStart} only has after reading {@code ctx.config(...)},
 * which a static {@code @Configuration} class has no access to. {@code OrdersServiceHooks}
 * registers it as a singleton instance directly on the {@code ApplicationContext} before {@code
 * refresh()}, and Spring resolves it into {@link #orderBook} below the same as any other bean.
 */
@Configuration(proxyBeanMethods = false)
public class OrdersConfiguration {

  @Bean
  public OrderIdFormatter orderIdFormatter() {
    return new OrderIdFormatter();
  }

  @Bean
  public OrderBook orderBook(DataSource dataSource, OrderIdFormatter orderIdFormatter) {
    return new OrderBook(dataSource, orderIdFormatter);
  }
}
