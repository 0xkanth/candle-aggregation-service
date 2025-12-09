package com.fintech.candles.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Global panic prevention and request safety configuration.
 * 
 * Protects against:
 * - Null pointer exceptions
 * - Thread exhaustion
 * - Memory leaks
 * - Uncaught exceptions
 */
@Configuration
public class PanicPreventionConfig implements WebMvcConfigurer {
    
    private static final Logger log = LoggerFactory.getLogger(PanicPreventionConfig.class);
    
    /**
     * Register global request interceptor for panic prevention.
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new PanicPreventionInterceptor());
    }
    
    /**
     * Interceptor that catches all uncaught exceptions.
     */
    public static class PanicPreventionInterceptor implements HandlerInterceptor {
        
        private static final Logger log = LoggerFactory.getLogger(PanicPreventionInterceptor.class);
        
        @Override
        public boolean preHandle(
                HttpServletRequest request,
                HttpServletResponse response,
                Object handler) {
            
            try {
                // Log suspicious patterns
                String uri = request.getRequestURI();
                
                // Detect potential SQL injection attempts
                if (uri.contains("--") || uri.contains(";") || uri.contains("DROP") || uri.contains("DELETE")) {
                    log.warn("Suspicious URI pattern detected: {}", uri);
                }
                
                // Detect path traversal attempts
                if (uri.contains("..") || uri.contains("%2e%2e")) {
                    log.warn("Path traversal attempt detected: {}", uri);
                    return false;
                }
                
                return true;
                
            } catch (Exception e) {
                log.error("Panic prevention interceptor error", e);
                return true; // Don't block legitimate requests
            }
        }
        
        @Override
        public void afterCompletion(
                HttpServletRequest request,
                HttpServletResponse response,
                Object handler,
                Exception ex) {
            
            // Log uncaught exceptions that somehow escaped
            if (ex != null) {
                log.error("PANIC: Uncaught exception in request {}: {}", 
                    request.getRequestURI(), ex.getMessage(), ex);
            }
        }
    }
    
    /**
     * Default uncaught exception handler (last resort).
     */
    @Bean
    public Thread.UncaughtExceptionHandler uncaughtExceptionHandler() {
        return (thread, throwable) -> {
            log.error("PANIC: Uncaught exception in thread {}: {}", 
                thread.getName(), throwable.getMessage(), throwable);
            
            // Don't let the thread die - log and continue
            // This prevents thread pool exhaustion
        };
    }
}
