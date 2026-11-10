package dev.studyplanner.common;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class OpenApiConfig {
    @Bean
    OpenAPI openApi() {
        return new OpenAPI().info(new Info().title("Study Planner API").version("0.1.0"))
                .components(new Components().addSecuritySchemes("bearer", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
