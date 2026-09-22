package io.converge.api;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfiguration {
    @Bean
    @Profile("prod")
    SecurityFilterChain productionSecurity(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**", "/webhooks/**"))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/webhooks/**").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/health").permitAll()
                        .requestMatchers("/api/**", "/actuator/prometheus").authenticated()
                        .anyRequest().permitAll())
                .httpBasic(Customizer.withDefaults())
                .build();
    }

    @Bean
    @Profile("prod")
    UserDetailsService productionUser(Environment environment, PasswordEncoder encoder) {
        return new InMemoryUserDetailsManager(User.withUsername(environment.getRequiredProperty("CONSOLE_USERNAME"))
                .password(encoder.encode(environment.getRequiredProperty("CONSOLE_PASSWORD")))
                .roles("OPERATOR").build());
    }

    @Bean
    @Profile("prod")
    PasswordEncoder productionPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    @Profile("!prod")
    SecurityFilterChain localSecurity(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }
}
