package br.com.backup;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Servidor relativo ("/") e info fixos: o contrato versionado não pode depender da porta usada
 * na geração (a task sobe o app em 7099) nem do host.
 */
@OpenAPIDefinition(info = @Info(title = "backup-service", version = "v1"), servers = @Server(url = "/"))
@SpringBootApplication
@ConfigurationPropertiesScan
public class BackupServiceApplication {
    public static void main(final String[] args) {
        SpringApplication.run(BackupServiceApplication.class, args);
    }
}
