package br.com.backup.config;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.introspection.OAuth2IntrospectionAuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * @author Junior Lima - oojuniiin@outlook.com
 * @since 12/09/2026
 */

public class WorkboxTokenIntrospector implements OpaqueTokenIntrospector {

  /** Prefixo das authorities geradas a partir de {@code modules} da introspecção (ex.: {@code MODULE_FINANCAS}); este serviço não usa módulo, só o papel ADMIN. */
  public static final String MODULE_AUTHORITY_PREFIX = "MODULE_";

  private final RestClient restClient;
  private final String introspectionUri;
  private final String clientId;
  private final String clientSecret;

  public WorkboxTokenIntrospector(final RestClient restClient, final String introspectionUri, final String clientId, final String clientSecret) {
    this.restClient = restClient;
    this.introspectionUri = introspectionUri;
    this.clientId = clientId;
    this.clientSecret = clientSecret;
  }

  @Override
  public OAuth2AuthenticatedPrincipal introspect(final String token) {
    final MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
    body.add("token",  token);

    Map<String, Object> result;
    try {
      result = restClient.post()
          .uri(introspectionUri)
          .headers(httpHeaders -> httpHeaders.setBasicAuth(clientId, clientSecret))
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .body(body)
          .retrieve()
          .body(new ParameterizedTypeReference<>() {});
    } catch (RestClientException e) {
      throw new BadOpaqueTokenException("Falha ao consultar introspecção no workbox-api");
    }

    if(result == null || !Boolean.TRUE.equals(result.get("active"))) {
      throw new BadOpaqueTokenException("Token inativo");
    }

      final Map<String, Object> attributes = new HashMap<>(result);
      final Object exp = attributes.get("exp");
    if (exp instanceof Number number) {
      attributes.put("exp", Instant.ofEpochSecond(number.longValue()));
    }

      @SuppressWarnings("unchecked") final
              List<String> roles = (List<String>) result.getOrDefault("roles", List.of());
      @SuppressWarnings("unchecked") final
              List<String> modules = (List<String>) result.getOrDefault("modules", List.of());
      final Collection<GrantedAuthority> authorities = Stream.concat(
              roles.stream(), modules.stream().map(module -> MODULE_AUTHORITY_PREFIX + module))
          .map(SimpleGrantedAuthority::new).collect(Collectors.toSet());

    return new OAuth2IntrospectionAuthenticatedPrincipal((String) result.get("sub"), attributes, authorities);
  }

}
