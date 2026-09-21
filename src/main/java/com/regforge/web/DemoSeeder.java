package com.regforge.web;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

@Configuration
public class DemoSeeder {

    @Bean
    CommandLineRunner seedDemo(ModelService modelService) {
        return args -> {
            if (modelService.store().count() > 0) {
                return;
            }
            try (var in = new ClassPathResource("demo.yaml").getInputStream()) {
                String yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                modelService.importRevision("demo_soc", yaml);
            }
        };
    }
}
