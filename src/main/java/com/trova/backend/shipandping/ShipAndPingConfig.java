package com.trova.backend.shipandping;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.hibernate.cfg.SessionEventSettings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
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
        String commit = build.get("commit");
        return new Sender(System.getenv("SHIP_AND_PING_KEY"),
                URI.create("https://ship-and-ping-ingest.taetaeg527.workers.dev/v1/ingest"),
                commit == null || commit.isBlank() ? "unknown" : commit, Duration.ofSeconds(30));
    }

    @Bean ApplicationListener<ApplicationReadyEvent> shipAndPingConnected(Sender sender, MeterRegistry registry) {
        return event -> sender.connect(AppProfile.current(),
                Path.of(System.getProperty("user.home"), ".trova", "shipandping-profile"),
                new ServerResources(registry), Duration.ofMinutes(1));
    }

    @Bean WebServerFactoryCustomizer<TomcatServletWebServerFactory> shipAndPingArrival(
            @Value("${ship.and.ping.trust.loopback.proxy:false}") boolean trustLoopbackProxy) {
        return factory -> factory.addEngineValves(new ArrivalValve(trustLoopbackProxy));
    }

    @Bean HibernatePropertiesCustomizer shipAndPingJdbcTime() {
        return settings -> settings.put(SessionEventSettings.AUTO_SESSION_EVENTS_LISTENER, RequestIoTime.class.getName());
    }

    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                if (request.getDispatcherType() == jakarta.servlet.DispatcherType.REQUEST
                        && request.getAttribute(RequestFilter.ACTIVE) instanceof RequestFilter.Observation obs) {
                    obs.handlerStarted();
                }
                return true;
            }
        }).addPathPatterns("/api/**").order(Ordered.LOWEST_PRECEDENCE);
    }

    @Bean FilterRegistrationBean<RequestFilter> shipAndPingFilter(Sender sender,
            @Value("${spring.mvc.async.request-timeout:10s}") Duration timeout) {
        FilterRegistrationBean<RequestFilter> registration = new FilterRegistrationBean<>(new RequestFilter(sender, timeout.toMillis()));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }

    @Override public void extendHandlerExceptionResolvers(List<HandlerExceptionResolver> resolvers) {
        resolvers.add(0, (request, response, handler, exception) -> {
            if (RequestFilter.disconnected(exception)) request.setAttribute(RequestFilter.ABORTED, true);
            request.setAttribute(RequestFilter.ERROR_CLASS, exception.getClass().getSimpleName());
            return null;
        });
    }
}
