package com.regforge.web;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.CharacterEncodingFilter;

@Configuration
public class WebEncodingConfig {

    @Bean
    FilterRegistrationBean<CharacterEncodingFilter> regforgeUtf8Filter() {
        CharacterEncodingFilter filter = new CharacterEncodingFilter("UTF-8", true, true);
        FilterRegistrationBean<CharacterEncodingFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/*");
        registration.setOrder(Integer.MIN_VALUE);
        return registration;
    }
}
