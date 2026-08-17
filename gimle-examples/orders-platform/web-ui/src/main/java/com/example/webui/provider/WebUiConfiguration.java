package com.example.webui.provider;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Same minimal explicit-{@code @Bean}, no-scanning shape as the other three modules' own
 * configuration classes. */
@Configuration(proxyBeanMethods = false)
public class WebUiConfiguration {

  @Bean
  public WebUiService webUiService() {
    return new WebUiService();
  }
}
