package com.amazon.sampleapp;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Component
public class DemoApplicationInterceptorConfig implements WebMvcConfigurer {
  @Autowired DemoApplicationInterceptor demoApplicationInterceptor;

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(demoApplicationInterceptor);
  }
}
