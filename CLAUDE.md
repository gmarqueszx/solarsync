# SolarSync — CLAUDE.md

Documento de referência do projeto para orientar o desenvolvimento assistido por IA.
Manter atualizado a cada decisão relevante — este arquivo é a fonte de verdade do escopo.

## 1. Contexto

A ConectSol controla hoje todo o processo de homologação de projetos solares (Coelba/Neoenergia)
por uma planilha Excel com 18 abas, uma por responsável ou subtipo de projeto. O objetivo do
SolarSync é substituir essa planilha por um sistema web com:

- API REST em Java/Spring Boot
- Banco PostgreSQL
- Frontend consumindo a API (design system: estilo Navan, paleta verde — ver seção 7)
- Autenticação e autorização por hierarquia (RBAC)
- Dashboard gerencial com métricas de tempo de ciclo, restrito ao gestor

O fluxo de negócio mapeado (a partir do processo real da ConectSol) tem 4 macro-etapas:

1. **Entrada e pendências** — cliente validado pelo financeiro entra no fluxo; verifica-se
   pendência na Coelba (troca de titularidade, ligação nova, extensão de rede, etc.). Sem
   pendência, segue direto. Com pendência, resolve-se na Coelba e atualiza-se manualmente em
   dois lugares (planilha + Trello) — **ponto de retrabalho identificado**.
2. **Débito e homologação** — verifica-se débito pendente do cliente (agência virtual Coelba).
   Com débito, cliente é cobrado até quitar. Sem débito, o projeto é preenchido e enviado à
   Coelba com ART.
3. **Acompanhamento** — status acompanhado por e-mail diário (aprovado / reprovado / em
   análise). Reprovado volta para correção e reenvio. Aprovado segue para vistoria.
4. **Vistoria e unificação** — vistoria solicitada pós-instalação; se aprovada, segue para
   pós-venda; se há unificação pendente, valida-se e desliga-se o medidor ou abre-se O.S.

## 2. Análise da planilha atual (`PLANILHA_TESTE_-_PROJETOS_.xlsx`)

18 abas, agrupáveis em 6 papéis funcionais:

| Abas de origem | Papel no processo | Observação |
|---|---|---|
| `PARAFAZER`, `PENDENCIASNYCOLLE`, `PENDENCIASOLICITACOES` | Fila de pendências (etapa 1) | Colunas: vendedor, data pagamento, cliente, pego, pendência, solicitado, status, conclusão |
| `IVAN`, `LARISSA`, `CAMILA` | Homologação de projeto (etapas 2–3) | Mesma estrutura em 3 abas duplicadas por analista: débito, data ART, data feito, data encaminhado, data reprova+motivo, data reencaminhado, data aprovação, dias para aprovação |
| `AUMENTO DE POTENCIA`, `AMPLIAÇOES`, `PROJETO COM UMA PLACA A MAIS`, `MUDANÇA DE INVERSOR 5KW`, `INVERSORES SEPARADOS` | Subtipos de projeto | Mesmo ciclo de vida do projeto — devem virar um campo `tipo_projeto`, não abas/tabelas separadas |
| `IGOR`, `UNIFICAÇÕES` | Unificação (etapa 4) | Cliente, cidade, projetista, info da unificação, feita?, desligamento |
| `DESLIGAMENTOS E ETC` | Desligamento de medidor (etapa 4) | Cliente, pego, status |
| `CLIENTES_DEBITOS` | Cache de status de débito | Cliente, status, última consulta |
| `CATS` | Protocolo/documento vinculado | Cliente, número, status |
| `Página12`, `Página20` | Lixo — confirmado, não migrar | — |

**Conclusão-chave**: a planilha é organizada por *responsável*, não por *etapa*. Isso duplica
trabalho (3 abas idênticas para Ivan/Larissa/Camila) e impede visão consolidada — exatamente o
que o dashboard do gestor precisa resolver.

## 3. Modelo de domínio proposto

Entidades centrais (nomes provisórios, ajustar durante desenvolvimento):

- **Cliente** — nome, cidade, vendedor, data_pagamento
- **Pendencia** — cliente_id, tipo, status, solicitado_em, resolvido_em, responsavel_id, observação
- **Debito** — cliente_id, status (ativo/quitado), última_consulta_em
- **Projeto** — cliente_id, tipo_projeto (padrão/ampliação/aumento_potência/mudança_inversor/...),
  analista_responsavel_id, data_recebimento, data_art, data_encaminhado, status
  (encaminhado/aprovado/reprovado/reencaminhado), motivo_reprova, data_aprovacao
- **Vistoria** — projeto_id, data_solicitacao, status (aprovada/reprovada), data_resultado
- **Unificacao** — cliente_id, cidade, projetista_id, informações, feita (bool), desligamento (bool)
- **Usuario** / **Papel** / **Permissao** — RBAC (ver seção 4)
- **HistoricoStatus** — tabela de auditoria (entidade_tipo, entidade_id, status_anterior,
  status_novo, timestamp, usuario_id) — **necessária para calcular todas as métricas do
  dashboard**, já que a planilha guarda só a data de cada evento pontual, não um histórico
  genérico

**Integração entre abas/etapas** (requisito do usuário): a mudança de status de uma entidade
deve dispara o avanço automático para a próxima. Ex.: `Pendencia.status = RESOLVIDA` cria/ativa
automaticamente o registro correspondente em `Projeto` com status inicial. Implementar via
evento de domínio (Spring `ApplicationEventPublisher` ou tabela de outbox) — não hardcoded em
controller.

## 4. RBAC

Hierarquia confirmada (implementação de permissões fica para depois — por ora só a estrutura
de papéis):

| Papel | Quem | Acesso |
|---|---|---|
| `ADMINISTRADOR` | João Gabriel | Acesso total ao sistema |
| `GESTOR` | Igor | Acesso total operacional + dashboard exclusivo |
| `ANALISTA` | Ivan, Larissa, Camila, Nycole e demais | Papel único — CRUD nas etapas do fluxo (pendência, débito, projeto, vistoria, unificação), sem distinção por especialidade dentro do sistema |

Implementação futura: Spring Security + JWT, `@PreAuthorize` por método, tabela
`papel`/`permissao` muitos-para-muitos com `usuario`. Não é prioridade da primeira fase.

## 5. Dashboard (exclusivo `GESTOR`)

Todas as métricas abaixo dependem de `HistoricoStatus` com timestamps confiáveis:

| Métrica | Cálculo (baseado no histórico) |
|---|---|
| Tempo médio sem ninguém mexer no cliente | `primeira_interação_ts - data_pagamento` |
| Tempo médio de resolução de pendência | `resolvido_em - solicitado_em` (Pendencia) |
| Tempo médio recebimento → envio do projeto | `data_encaminhado - data_recebimento` (Projeto) |
| Tempo médio para aprovação | `data_aprovacao - data_encaminhado` (última vez encaminhado) |
| Tempo médio parado por débito | `debito_quitado_em - debito_detectado_em` |
| Tempo médio para solicitar vistoria pós-instalação | `data_solicitacao_vistoria - data_instalado` |
| Tempo médio de ciclo completo | `data_aprovacao_vistoria - data_recebimento_projeto` |
| Quantitativo: pendências resolvidas | `COUNT(Pendencia WHERE status = RESOLVIDA)` |
| Quantitativo: projetos aprovados | `COUNT(Projeto WHERE status = APROVADO)` |
| Quantitativo: clientes com débito parado | `COUNT(Debito WHERE status = ATIVO)` |
| Quantitativo: projetos encaminhados | `COUNT(Projeto WHERE status = ENCAMINHADO)` |
| Quantitativo: projetos reprovados | `COUNT(Projeto WHERE status = REPROVADO)` |
| Quantitativo: vistorias solicitadas | `COUNT(Vistoria)` |

## 6. Arquitetura técnica

- **Backend**: Java 21, Spring Boot 3.x (Web, Security, Data JPA, Validation), Maven
- **Banco**: PostgreSQL, migrações com Flyway
- **Auth**: JWT (access + refresh token), RBAC via `@PreAuthorize`
- **Frontend**: estilo Navan em paleta verde (ver seção 7) — deve consumir a API via REST/JSON
- **Infra local**: Docker Compose (API + Postgres), alinhado ao ambiente já usado no VPS Contabo
- **Estrutura de pacotes sugerida**:
  ```
  com.conectsol.solarsync
  ├── cliente
  ├── pendencia
  ├── debito
  ├── projeto
  ├── vistoria
  ├── unificacao
  ├── auth (usuario, papel, permissao, jwt)
  ├── historico (auditoria de status)
  ├── dashboard
  └── common (config, exceptions, eventos de domínio)
  ```

## 7. Design system

Referência: estilo Navan (SaaS enterprise), adaptado para paleta verde a pedido do usuário.

- **Cor primária**: `#149911` (botões, links, estados ativos) — hover/escuro `#256D1B`
- **Sidebar/header escuro**: `#244F26`
- **Neutro de apoio** (texto secundário, bordas): `#424342`
- **Destaque pontual** (badge "novo", indicador ativo — nunca fundo de botão ou texto): `#1EFC1E`
- **Neutros**: branco para cards e superfícies de conteúdo, cinza claro para o fundo da página,
  quase-preto (`#13151A`-like, no estilo Navan) reservado para sidebar/header ou modo escuro
- **Tipografia**: sans-serif limpa (Inter ou equivalente), pesos 400/500 apenas, tamanhos
  moderados — nada de exagero decorativo
- **Layout**: sidebar de navegação fixa + topbar, conteúdo em cards com `border-radius` generoso
  (12-16px), tabelas com divisores sutis (sem bordas pesadas), badges em formato pílula para
  status (ex.: pendente / resolvido / aprovado / reprovado) coloridos por semântica dentro da
  escala verde + neutros
- **Componentes-chave que o SolarSync precisa**: sidebar com os módulos (Pendências, Débitos,
  Projetos, Vistoria, Unificação, Dashboard), tabela de listagem com filtro e paginação, cards de
  métricas (KPI) no dashboard, badges de status, formulário de detalhe/edição por entidade

Pendente: gerar escala completa (50-900) a partir de `#149911` no uicolors.app quando for
implementar o CSS/tema.

## 8. Checklist de produção (baseado no guia dos 12 itens)

Adaptação dos 12 itens do guia Mestre-Code para o contexto do SolarSync — sistema interno de
uma única empresa (ConectSol), não SaaS multi-cliente. Isso muda a prioridade de alguns itens.

| # | Item | Aplica? | Nota para o SolarSync |
|---|---|---|---|
| 1 | PRD | **Sim** | Este próprio CLAUDE.md cumpre esse papel — manter atualizado a cada decisão |
| 2 | Mapa do sistema (UML) | **Sim** | Falta gerar: diagrama de classes das entidades da seção 3 e diagrama de sequência do fluxo de status (pendência → projeto → vistoria) |
| 3 | RBAC (matriz completa) | **Sim, expandir** | Seção 4 define os papéis, mas falta matriz explícita ação × papel (ex.: quem pode editar débito, quem pode ver dashboard) antes de implementar |
| 4 | Multi-tenancy | **Não se aplica** | Sistema é de uma empresa só (ConectSol), não atende múltiplos clientes-empresa na mesma base. Não criar isolamento por tenant |
| 5 | RLS no banco | **Opcional, defesa extra** | Sem multi-tenancy o risco principal muda: RLS aqui serviria só se quiser reforçar "analista só vê clientes atribuídos a si" dentro do próprio Postgres, além do controle no Spring Security. Não é bloqueante, mas vale considerar para o dashboard financeiro (débitos) |
| 6 | Nenhuma senha no código | **Sim, obrigatório** | `.env` fora do Git, credenciais do Postgres e JWT secret como variável de ambiente no VPS Contabo, nunca commitadas |
| 7 | Arquitetura modular (liga/desliga por cliente) | **Não se aplica como catálogo comercial** | Não há "clientes-empresa" comprando módulos. Mas a separação em pacotes por etapa (seção 6) já cumpre o espírito de baixo acoplamento entre módulos |
| 8 | Botão de reportar problema | **Sim, recomendado** | Útil dado que analistas vão operar o sistema diariamente — botão de feedback com captura de tela/contexto ajuda a substituir o "manda áudio de 3 minutos" |
| 9 | Testes automáticos | **Sim, obrigatório** | Crítico especificamente para a regra de integração entre etapas (seção 3: pendência resolvida → cria projeto automaticamente) e para o cálculo das métricas do dashboard — são os dois pontos onde um bug silencioso derruba a confiança do gestor no sistema |
| 10 | Auditoria de segurança | **Sim, antes de ir ao ar** | Sistema guarda dado de cliente final (nome, débito, projeto) — mesmo sendo uso interno, uma auditoria básica (dependências desatualizadas, endpoints sem autenticação, RBAC sem furo) antes do deploy no VPS Contabo é razoável |
| 11 | WAF / rate limiting | **Sim** | VPS Contabo expõe a aplicação à internet — recomentélo Cloudflare (gratuito) na frente, rate limit no endpoint de login |
| 12 | HTTPS/TLS | **Sim, obrigatório** | Certificado (Let's Encrypt) + redirecionamento forçado HTTPS no domínio usado no VPS Contabo |

**Resumo prático**: dos 12, os itens 4 e 7 não se aplicam no sentido original (são pensados pra
SaaS multi-cliente); o 5 é opcional; os outros 9 valem para o SolarSync.

## 9. Próximos passos

1. Fechar modelo de dados (DDL inicial + Flyway migration V1)
2. Definir contratos REST (OpenAPI) para cada módulo
3. Prototipar dashboard com dados mockados até planilha ser migrada
4. Escrever script de importação da planilha atual para o banco novo
