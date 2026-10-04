package br.com.backup.controllers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.backup.config.SecurityConfig;
import br.com.backup.exceptions.BackupFailedException;
import br.com.backup.exceptions.BackupInProgressException;
import br.com.backup.exceptions.BackupNotFoundException;
import br.com.backup.exceptions.InvalidBackupRequestException;
import br.com.backup.models.BackupInfo;
import br.com.backup.models.BackupRequest;
import br.com.backup.services.BackupService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.introspection.OAuth2IntrospectionAuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Usa o {@link SecurityConfig} real; só o introspector (chamada HTTP ao workbox-api) é substituído. */
@WebMvcTest(BackupController.class)
@Import(SecurityConfig.class)
class BackupControllerTest {

    private static final String BASE = "/api/v1/backups";
    private static final String ADMIN = "token-admin";
    private static final String USER = "token-user";
    private static final String ID = "workbox_20261004_101500";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BackupService backupService;

    @MockitoBean
    private OpaqueTokenIntrospector introspector;

    @TempDir
    Path dir;

    @BeforeEach
    void stubIntrospector() {
        when(introspector.introspect(ADMIN)).thenReturn(principal("qa.admin@workbox.local", "ROLE_ADMIN", "MODULE_FINANCAS"));
        when(introspector.introspect(USER)).thenReturn(principal("qa.user@workbox.local", "ROLE_USER", "MODULE_FINANCAS"));
        when(introspector.introspect("revogado")).thenThrow(new BadOpaqueTokenException("Token inativo"));
    }

    private static OAuth2AuthenticatedPrincipal principal(final String name, final String... authorities) {
        return new OAuth2IntrospectionAuthenticatedPrincipal(name, Map.of("sub", name),
                java.util.Arrays.stream(authorities).<org.springframework.security.core.GrantedAuthority>map(SimpleGrantedAuthority::new).toList());
    }

    private static MockHttpServletRequestBuilder as(final String token, final MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + token);
    }

    private static BackupInfo info(final boolean encrypted) {
        return new BackupInfo(ID, ID + (encrypted ? ".dump.enc" : ".dump"), "/home/jr/backups/" + ID + ".dump", 2048L, "ab".repeat(32),
                Instant.parse("2026-10-04T10:15:00Z"), "qa.admin@workbox.local", encrypted);
    }

    @Test
    void semToken_respondeUnauthorized() throws Exception {
        mockMvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        verifyNoInteractions(backupService);
    }

    @Test
    void tokenRevogado_respondeUnauthorized() throws Exception {
        mockMvc.perform(as("revogado", get(BASE))).andExpect(status().isUnauthorized());
    }

    @Test
    void usuarioComumMesmoComOutroModulo_respondeForbidden() throws Exception {
        mockMvc.perform(as(USER, get(BASE))).andExpect(status().isForbidden());
        mockMvc.perform(as(USER, post(BASE).contentType(MediaType.APPLICATION_JSON).content("{}"))).andExpect(status().isForbidden());
        mockMvc.perform(as(USER, get(BASE + "/" + ID + "/download"))).andExpect(status().isForbidden());
        mockMvc.perform(as(USER, delete(BASE + "/" + ID))).andExpect(status().isForbidden());
        verifyNoInteractions(backupService);
    }

    @Test
    void listar_admin_devolveOsBackups() throws Exception {
        when(backupService.listar()).thenReturn(List.of(info(false)));

        mockMvc.perform(as(ADMIN, get(BASE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(ID))
                .andExpect(jsonPath("$[0].path").value("/home/jr/backups/" + ID + ".dump"))
                .andExpect(jsonPath("$[0].sizeBytes").value(2048))
                .andExpect(jsonPath("$[0].encrypted").value(false));
    }

    @Test
    void gerar_admin_respondeCreatedEPassaOEmailDoSolicitante() throws Exception {
        when(backupService.gerar(eq("qa.admin@workbox.local"), any(BackupRequest.class))).thenReturn(info(true));

        mockMvc.perform(as(ADMIN, post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"passphrase\":\"uma-senha-bem-forte-123\",\"passphraseConfirmation\":\"uma-senha-bem-forte-123\"}")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.encrypted").value(true))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("uma-senha"))));

        verify(backupService).gerar("qa.admin@workbox.local", new BackupRequest("uma-senha-bem-forte-123", "uma-senha-bem-forte-123"));
    }

    @Test
    void gerar_semCorpo_funcionaComoBackupSemSenha() throws Exception {
        when(backupService.gerar(eq("qa.admin@workbox.local"), any(BackupRequest.class))).thenReturn(info(false));

        mockMvc.perform(as(ADMIN, post(BASE))).andExpect(status().isCreated());

        verify(backupService).gerar("qa.admin@workbox.local", new BackupRequest(null, null));
    }

    @Test
    void gerar_requisicaoInvalida_respondeBadRequestEmProblemDetail() throws Exception {
        when(backupService.gerar(any(), any())).thenThrow(new InvalidBackupRequestException("A senha deve ter pelo menos 12 caracteres"));

        mockMvc.perform(as(ADMIN, post(BASE).contentType(MediaType.APPLICATION_JSON).content("{\"passphrase\":\"curta\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("A senha deve ter pelo menos 12 caracteres"));
    }

    @Test
    void gerar_jaEmAndamento_respondeConflict() throws Exception {
        when(backupService.gerar(any(), any())).thenThrow(new BackupInProgressException());

        mockMvc.perform(as(ADMIN, post(BASE))).andExpect(status().isConflict());
    }

    @Test
    void gerar_falhaDoPgDump_respondeErroGenericoSemDetalheInterno() throws Exception {
        when(backupService.gerar(any(), any())).thenThrow(new BackupFailedException("Não foi possível gerar o backup. Veja os logs do serviço."));

        mockMvc.perform(as(ADMIN, post(BASE)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("Não foi possível gerar o backup. Veja os logs do serviço."));
    }

    @Test
    void download_admin_devolveOArquivoComoAnexo() throws Exception {
        final var file = dir.resolve(ID + ".dump");
        Files.writeString(file, "PGDMP-conteudo");
        when(backupService.arquivo(ID)).thenReturn(new BackupService.BackupFile(file, ID + ".dump"));

        mockMvc.perform(as(ADMIN, get(BASE + "/" + ID + "/download")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment; filename=\"" + ID + ".dump\"")))
                .andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM))
                .andExpect(content().string("PGDMP-conteudo"));
    }

    @Test
    void download_inexistente_respondeNotFound() throws Exception {
        when(backupService.arquivo("naoexiste")).thenThrow(new BackupNotFoundException("naoexiste"));

        mockMvc.perform(as(ADMIN, get(BASE + "/naoexiste/download"))).andExpect(status().isNotFound());
    }

    @Test
    void excluir_admin_respondeNoContent() throws Exception {
        mockMvc.perform(as(ADMIN, delete(BASE + "/" + ID))).andExpect(status().isNoContent());

        verify(backupService).excluir(ID);
    }

    @Test
    void excluir_inexistente_respondeNotFound() throws Exception {
        doThrow(new BackupNotFoundException("x")).when(backupService).excluir("x");

        mockMvc.perform(as(ADMIN, delete(BASE + "/x"))).andExpect(status().isNotFound());
    }
}
