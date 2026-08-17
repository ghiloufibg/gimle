package com.example.inventory.provider;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Same minimal explicit-{@code @Bean}, no-scanning shape as orders-service's own configuration
 * class -- see its javadoc for why. */
@Configuration(proxyBeanMethods = false)
public class InventoryConfiguration {

  @Bean
  public StockLedger stockLedger() {
    return new StockLedger();
  }
}
