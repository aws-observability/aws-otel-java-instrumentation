package com.amazon.sampleapp;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

@Component
public class DemoApplicationInterceptorConfig implements WebMvcConfigurer {
    @Autowired
    DemoApplicationInterceptor demoApplicationInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(demoApplicationInterceptor);
    }
}