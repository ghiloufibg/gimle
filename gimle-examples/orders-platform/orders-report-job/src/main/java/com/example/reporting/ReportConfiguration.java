package com.example.reporting;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Same minimal explicit-{@code @Bean}, no-scanning shape as the other two services' own
 * configuration classes. */
@Configuration(proxyBeanMethods = false)
public class ReportConfiguration {

  @Bean
  public ReportGenerator reportGenerator() {
    return new ReportGenerator();
  }
}
