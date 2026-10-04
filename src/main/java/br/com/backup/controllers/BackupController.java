package br.com.backup.controllers;

import br.com.backup.models.BackupInfo;
import br.com.backup.models.BackupRequest;
import br.com.backup.services.BackupService;
import java.util.List;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Backups do banco — tudo ADMIN-only (ver SecurityConfig). Não há endpoint de restore: só por script. */
@RestController
@RequestMapping("/api/v1/backups")
public class BackupController {

    private final BackupService backupService;

    public BackupController(final BackupService backupService) {
        this.backupService = backupService;
    }

    /** Gera um backup agora (síncrono). Corpo opcional: {@code passphrase}/{@code passphraseConfirmation} cifram o arquivo. */
    @PostMapping
    public ResponseEntity<BackupInfo> gerar(final Authentication authentication, @RequestBody(required = false) final BackupRequest request) {
        final var info = backupService.gerar(authentication.getName(), request == null ? new BackupRequest(null, null) : request);
        return ResponseEntity.status(HttpStatus.CREATED).body(info);
    }

    @GetMapping
    public List<BackupInfo> listar() {
        return backupService.listar();
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> baixar(@PathVariable final String id) {
        final var file = backupService.arquivo(id);
        final var disposition = ContentDisposition.attachment().filename(file.fileName()).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(file.path()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> excluir(@PathVariable final String id) {
        backupService.excluir(id);
        return ResponseEntity.noContent().build();
    }
}
