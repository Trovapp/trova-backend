package com.trova.backend.shipandping;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
@Conditional(ShipAndPingConfig.Enabled.class)
class ShipAndPingConfig implements WebMvcConfigurer {
    static class Enabled implements Condition {
        @Override public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String key = System.getenv("SHIP_AND_PING_KEY");
            return key != null && !key.isBlank();
        }
    }

    @Bean Sender shipAndPingSender(BuildProperties build) {
        return new Sender(System.getenv("SHIP_AND_PING_KEY"),
                URI.create("https://ship-and-ping-ingest.taetaeg527.workers.dev/v1/ingest"),
                build.getTime().toString(), Duration.ofSeconds(30));
    }

    @Bean FilterRegistrationBean<RequestFilter> shipAndPingFilter(Sender sender) {
        FilterRegistrationBean<RequestFilter> registration = new FilterRegistrationBean<>(new RequestFilter(sender));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }

    @Override public void extendHandlerExceptionResolvers(List<HandlerExceptionResolver> resolvers) {
        resolvers.add(0, (request, response, handler, exception) -> {
            request.setAttribute(RequestFilter.ERROR_CLASS, exception.getClass().getSimpleName());
            return null;
        });
    }
}
