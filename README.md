# backup-service

Backup do banco Postgres do Workbox pela aplicação: gera um `pg_dump` (todos os schemas), guarda na
pasta `./backups` do host, mostra o caminho do arquivo e deixa baixar. Opcionalmente cifra o arquivo
com uma senha que você digita na hora. **Não faz restore** — isso é só por `scripts/restore-db.sh`.

## Endpoints (todos exigem Bearer de um **ADMIN**)

| Método e rota | O que faz |
|---|---|
| `POST /api/v1/backups` | Gera um backup agora (síncrono, 201). Corpo opcional `{"passphrase","passphraseConfirmation"}` cifra o arquivo (mín. 12 caracteres). 409 se já há um em andamento |
| `GET /api/v1/backups` | Lista os backups da pasta, do mais novo ao mais antigo (id, arquivo, **caminho no host**, tamanho, sha256, quando, quem, cifrado) |
| `GET /api/v1/backups/{id}/download` | Baixa o arquivo (`attachment`) |
| `DELETE /api/v1/backups/{id}` | Apaga o arquivo e os metadados |

Contrato: [`openapi/openapi.yaml`](openapi/openapi.yaml). Erros em RFC 9457.

## Arquivos
`<banco>_<yyyyMMdd_HHmmss>.dump` (ou `.dump.enc` se cifrado, UTC) + `.meta.json` ao lado. Para restaurar
um cifrado: `scripts/decrypt-backup.sh backups/<arquivo>.dump.enc` (pede a senha) e depois
`docker compose --profile backup run --rm restore /backups/<arquivo>.dump`. **Sem a senha não há como
recuperar o backup.** O dump tem hashes de senha e segredos de MFA: guarde em lugar seguro.

## Configuração (env)

| Variável | Default | |
|---|---|---|
| `BACKUP_DIR` | `/backups` | pasta onde grava (dentro do container) |
| `BACKUP_HOST_DIR` | vazio | caminho equivalente no host, exibido na tela |
| `PGHOST` `PGPORT` `PGDATABASE` | `localhost` `7050` `workbox` | banco |
| `PGUSER` `PGPASSWORD` | `backup_service` | role dedicado (`pg_read_all_data`) |
| `INTROSPECTION_URI`/`_CLIENT_ID`/`_CLIENT_SECRET` | ver `application.properties` | cliente em `workbox.api_clients` |

### Role do banco (uma vez; `initdb/` só roda em volume vazio)
```sql
CREATE ROLE backup_service WITH LOGIN PASSWORD '<senha>';
GRANT CONNECT ON DATABASE workbox TO backup_service;
GRANT pg_read_all_data TO backup_service;
```

## Desenvolvimento
`./gradlew test` · `./gradlew generateOpenApiDocs` · porta 7058. Requer `pg_dump` 18 e `openssl` no PATH
para rodar fora do Docker.
