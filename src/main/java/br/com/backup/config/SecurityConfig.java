package br.com.backup.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestClient;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Resource server: valida os access tokens do workbox-api por introspecção remota (client credentials), sem login
 * próprio. Diferente dos outros serviços, o acesso aqui não é por módulo: um backup contém o banco inteiro (hashes de
 * senha, segredos de MFA), então só o papel {@code ADMIN} entra — autenticado sem ADMIN = 403.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    static final String ROLE_ADMIN_AUTHORITY = "ROLE_ADMIN";

    @Value("${introspection.uri}")
    private String introspectionUri;

    @Value("${introspection.client-id}")
    private String introspectionClientId;

    @Value("${introspection.client-secret}")
    private String introspectionClientSecret;

    @Value("${cors.allowed-origins:http://localhost:7053,http://127.0.0.1:7053}")
    private List<String> allowedOrigins;

    /** Filter chain única do serviço — todo endpoint exige token válido de <b>ADMIN</b>, exceto health e Swagger. */
    @Bean
    public SecurityFilterChain securityFilterChain(final HttpSecurity httpSecurity, final OpaqueTokenIntrospector introspector) throws Exception {
        httpSecurity.csrf(AbstractHttpConfigurer::disable);
        httpSecurity.httpBasic(AbstractHttpConfigurer::disable);
        httpSecurity.cors(cors -> cors.configurationSource(corsConfigurationSource()));

        httpSecurity.authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml").permitAll()
                .anyRequest().hasAuthority(ROLE_ADMIN_AUTHORITY)
        );

        httpSecurity.oauth2ResourceServer(oauth2 -> oauth2.opaqueToken(opaque -> opaque.introspector(introspector)));
        return httpSecurity.build();
    }

    /** Origens liberadas pra chamadas com credenciais — {@code cors.allowed-origins}, default aponta pro workbox-app local. */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        final var configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of("Content-Disposition"));
        configuration.setAllowCredentials(true);

        final var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /** Introspector custom: o token é opaco (não JWT decodificável aqui), validado no workbox-api via client credentials. */
    @Bean
    public OpaqueTokenIntrospector opaqueTokenIntrospector() {
        return new WorkboxTokenIntrospector(RestClient.create(), introspectionUri, introspectionClientId, introspectionClientSecret);
    }
}
