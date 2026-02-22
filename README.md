# MailVault

UI minima para navegar e ler emails indexados via SQLite.

## Executar

```bash
cd backend
MAILVAULT_INDEX_ROOT_DIR=./data/emails \\
MAILVAULT_STORAGE_DIR=./data/storage \\
./gradlew bootRun
```

A aplicacao sobe em `http://localhost:8080`.

## Usar a UI

- Caixa historica (lista e busca): `GET /`
- Detalhe da mensagem: `GET /messages/{id}`
- Reindexacao manual no detalhe: botao **Reindexar** (chama `POST /api/index`)
- Render HTML sanitizado: `GET /api/messages/{id}/render`
- Navegacao externa segura (links): `GET /go?url=...`

## Renderizacao HTML segura

- HTML bruto de emails (`html_raw`) e reescrito para:
  - links `<a href>` -> `/go?url=...`
  - imagens `cid:` -> `/api/messages/{id}/cid/{cid}`
  - imagens remotas `http/https` -> `/static/remote-image-blocked.svg` com `data-original-src`
- O HTML final e sanitizado com OWASP Java HTML Sanitizer e cacheado em `html_sanitized`.
- Elementos ativos/perigosos (ex.: `script`, `iframe`, `form`, handlers `on*`, `javascript:`) sao bloqueados.

## Fluxo rapido

1. Configure `MAILVAULT_INDEX_ROOT_DIR` apontando para o diretorio mapeado com arquivos `.eml`.
2. (Opcional) Configure `MAILVAULT_STORAGE_DIR` para o volume de anexos.
3. (Opcional) Configure limites:
   - `MAILVAULT_MAX_ASSETS_PER_MESSAGE` (default `64`)
   - `MAILVAULT_MAX_ASSET_BYTES` (default `10485760`)
4. Abra `http://localhost:8080/` e busque mensagens.
5. Clique em um item para abrir `http://localhost:8080/messages/{id}` e ler `text/plain`.

## Migrations recentes

- `V5__html.sql`: adiciona `html_raw` e `html_sanitized` em `message_bodies`
- `V6__attachments.sql`: cria tabela `attachments` para metadados e path de storage
