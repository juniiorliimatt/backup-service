package br.com.backup.services;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** {@link CommandRunner} real, sobre {@link ProcessBuilder}. Sem shell: os argumentos vão direto ao binário (sem injeção). */
@Component
public class ProcessCommandRunner implements CommandRunner {

    private static final long TIMEOUT_MINUTES = 30;
    private static final int MAX_OUTPUT_CHARS = 8_000;

    @Override
    public CommandResult run(final List<String> command, final Map<String, String> environment, final String stdin) throws IOException {
        final var builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().putAll(environment);
        final var process = builder.start();
        try {
            try (var out = process.getOutputStream()) {
                if (stdin != null) {
                    out.write(stdin.getBytes(StandardCharsets.UTF_8));
                }
            }
            final var output = new String(process.getInputStream().readNBytes(MAX_OUTPUT_CHARS), StandardCharsets.UTF_8);
            // O restante do output (se houver) precisa ser drenado, senão o processo trava com o pipe cheio.
            process.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                return new CommandResult(-1, "tempo esgotado (" + TIMEOUT_MINUTES + " min)");
            }
            return new CommandResult(process.exitValue(), output);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Execução interrompida", e);
        }
    }
}
