package br.com.backup.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class WorkboxTokenIntrospectorTest {

    private static final String URI = "http://workbox-api/api/v1/auth/introspect";

    private MockRestServiceServer server;
    private WorkboxTokenIntrospector introspector;

    @BeforeEach
    void setUp() {
        final var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        introspector = new WorkboxTokenIntrospector(builder.build(), URI, "backup-service", "segredo");
    }

    @Test
    void introspect_activeToken_mapsRolesAndModulesToAuthorities() {
        server.expect(requestTo(URI)).andRespond(withSuccess(
                "{\"active\":true,\"sub\":\"qa.admin@workbox.local\",\"roles\":[\"ROLE_ADMIN\"],\"modules\":[\"FINANCAS\"],\"exp\":1760000000}",
                MediaType.APPLICATION_JSON));

        final var principal = introspector.introspect("abc");

        assertThat(principal.getName()).isEqualTo("qa.admin@workbox.local");
        assertThat(principal.getAuthorities()).extracting("authority").containsExactlyInAnyOrder("ROLE_ADMIN", "MODULE_FINANCAS");
    }

    @Test
    void introspect_inactiveToken_throwsBadOpaqueToken() {
        server.expect(requestTo(URI)).andRespond(withSuccess("{\"active\":false}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> introspector.introspect("t")).isInstanceOf(BadOpaqueTokenException.class);
    }

    @Test
    void introspect_workboxApiFailure_throwsBadOpaqueTokenWithoutLeakingTheError() {
        server.expect(requestTo(URI)).andRespond(withServerError());

        assertThatThrownBy(() -> introspector.introspect("t")).isInstanceOf(BadOpaqueTokenException.class).hasMessageContaining("introspecção");
    }
}
