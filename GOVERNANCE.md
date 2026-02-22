Antes de implementar qualquer feature no MailVault, siga estas regras obrigatórias.

Objetivo
Manter o projeto simples, seguro, consistente e incremental. Não adicionar complexidade desnecessária.

Arquitetura obrigatória

1) Estrutura de pacotes (não alterar):
   dev.marcal.mailvault
    - api        -> DTOs (request/response)
    - web        -> Controllers REST
    - service    -> lógica de negócio
    - repository -> acesso a dados via JdbcTemplate
    - domain     -> modelos internos
    - config     -> configurações Spring
    - util       -> helpers técnicos

2) Persistência
- Usar JdbcTemplate (NÃO usar JPA/Hibernate)
- SQLite como banco principal
- Flyway para todas as mudanças de schema
- Nenhuma tabela criada fora de migration

3) Estilo de código
- Kotlin idiomático
- Data classes para DTOs e domain
- Sem classes gigantes (>300 linhas)
- Um serviço por responsabilidade clara
- Sem lógica pesada dentro de controllers
- Controllers apenas orquestram e retornam DTO

4) Erros
- Criar GlobalExceptionHandler (@ControllerAdvice)
- Retornar JSON padrão:
  {
  "error": "CODE",
  "message": "Descrição clara",
  "timestamp": "ISO-8601"
  }
- Não retornar stacktrace ao cliente

5) Logging
- Usar logger padrão do Spring
- Nunca logar:
    - conteúdo completo do email
    - anexos
    - HTML bruto
- Pode logar id da mensagem, path e erros resumidos

6) Segurança obrigatória
- Nunca renderizar HTML sem sanitização
- Nunca carregar recursos remotos automaticamente
- Bloquear qualquer URL com esquema diferente de http/https
- Preparar código para SSRF guard no futuro
- Servir arquivos com:
    - X-Content-Type-Options: nosniff
    - Content-Disposition apropriado
- Não usar eval, reflection dinâmica desnecessária ou execução arbitrária

7) Configuração
   Criar MailVaultProperties em config:
- rootEmailsDir (default: ./data/emails)
- storageDir (default: ./data/storage)
- maxAssetsPerMessage
- maxAssetBytes
- etc (expansível)

Carregar via @ConfigurationProperties.

8) Performance
- Sempre paginar listagens
- Nunca carregar todos os emails em memória
- Streams para leitura de arquivos grandes
- Limitar downloads por tamanho

9) Testes
- Criar pelo menos:
    - 1 teste de contexto (já existe)
    - 1 teste de repository
    - 1 teste de serviço principal quando relevante
- Não é necessário cobertura total, mas testar partes críticas

10) README
    Sempre atualizar README quando:
- adicionar migration
- adicionar endpoint
- adicionar configuração nova

11) Docker-ready
- Não usar caminhos absolutos
- Tudo configurável por properties
- Banco e storage devem funcionar via volume

12) Regra de simplicidade
    Se houver duas soluções:
- escolha a mais simples que resolva o problema
- evite frameworks adicionais
- evite abstrações prematuras

13) Regra de incrementalidade
    Antes de implementar qualquer feature grande:
- Escreva um plano curto (checklist)
- Depois implemente

14) Proibido
- Adicionar autenticação
- Adicionar multi-tenant
- Adicionar fila assíncrona
- Adicionar cache distribuído
- Adicionar features fora do escopo definido nos prompts seguintes

15) Critério geral de qualidade
    O código deve:
- Compilar
- Subir com ./gradlew bootRun
- Não gerar warnings críticos
- Ter separação clara de responsabilidades

Agora confirme entendimento das regras e aguarde o próximo prompt de feature.

Regra adicional obrigatória para qualquer nova feature no MailVault:

Antes de escrever código, você DEVE:

1) Escrever um plano curto em checklist (máximo 12 itens)
2) Explicar rapidamente quais arquivos serão criados/alterados
3) Explicar quais migrations serão necessárias (se houver)
4) Confirmar critérios de aceite

Somente após apresentar o plano, aguarde confirmação antes de implementar.

Objetivo:
- Evitar implementação precipitada
- Garantir arquitetura coerente
- Reduzir retrabalho
- Manter incrementalidade

Nunca pule essa etapa.