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
- Inline CID: `GET /api/messages/{id}/cid/{cid}`
- Lista de anexos: `GET /api/messages/{id}/attachments`
- Download de anexo: `GET /api/attachments/{attachmentId}/download`
- Freeze de imagens remotas: `POST /api/messages/{id}/freeze-assets`
- Servir assets congelados: `GET /assets/{messageId}/{filename}`

## Renderizacao HTML segura

- HTML bruto de emails (`html_raw`) e reescrito para:
  - links `<a href>` -> `/go?url=...`
  - imagens `cid:` -> `/api/messages/{id}/cid/{cid}`
  - imagens remotas `http/https` -> `/static/remote-image-blocked.svg` com `data-original-src`
- O HTML final e sanitizado com OWASP Java HTML Sanitizer e cacheado em `html_sanitized`.
- Elementos ativos/perigosos (ex.: `script`, `iframe`, `form`, handlers `on*`, `javascript:`) sao bloqueados.
- Imagens remotas so aparecem apos freeze; antes disso, sao substituidas por placeholder local.

## Fluxo rapido

1. Configure `MAILVAULT_INDEX_ROOT_DIR` apontando para o diretorio mapeado com arquivos `.eml`.
2. (Opcional) Configure `MAILVAULT_STORAGE_DIR` para o volume de anexos.
3. (Opcional) Configure limites:
   - `MAILVAULT_MAX_ASSETS_PER_MESSAGE` (default `50`)
   - `MAILVAULT_MAX_ASSET_BYTES` (default `10485760`)
   - `MAILVAULT_TOTAL_MAX_BYTES_PER_MESSAGE` (default `52428800`)
   - `MAILVAULT_ASSET_CONNECT_TIMEOUT_SECONDS` (default `5`)
   - `MAILVAULT_ASSET_READ_TIMEOUT_SECONDS` (default `10`)
4. Abra `http://localhost:8080/` e busque mensagens.
5. Clique em um item para abrir `http://localhost:8080/messages/{id}` e ler `text/plain`/HTML.
6. No detalhe, use **Congelar imagens** para baixar imagens remotas com limites e protecao SSRF.

## Migrations recentes

- `V5__html.sql`: adiciona `html_raw` e `html_sanitized` em `message_bodies`
- `V6__attachments.sql`: cria tabela `attachments` para metadados e path de storage
- `V7__assets.sql`: cria tabela `assets` para freeze de imagens remotas e cache local
