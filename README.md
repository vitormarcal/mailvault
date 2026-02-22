# MailVault

UI minima para navegar e ler emails indexados via SQLite.

## Executar

```bash
cd backend
MAILVAULT_INDEX_ROOT_DIR=./data/emails ./gradlew bootRun
```

A aplicacao sobe em `http://localhost:8080`.

## Usar a UI

- Caixa historica (lista e busca): `GET /`
- Detalhe da mensagem: `GET /messages/{id}`
- Reindexacao manual no detalhe: botao **Reindexar** (chama `POST /api/index`)

## Fluxo rapido

1. Configure `MAILVAULT_INDEX_ROOT_DIR` apontando para o diretorio mapeado com arquivos `.eml`.
2. Abra `http://localhost:8080/` e busque mensagens.
3. Clique em um item para abrir `http://localhost:8080/messages/{id}` e ler `text/plain`.
