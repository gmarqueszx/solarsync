-- Log de atividades: quem criou, editou ou excluiu cada registro, quando, e o antes/depois de
-- cada campo. Pedido do usuário em 16/11/2026 ("um log dentro do aplicativo, onde mostra qual
-- usuário fez cada alteração, hora, e etc.").
--
-- Tabela própria, e não mais linhas em historico_status, de propósito: o historico_status
-- sustenta as métricas do dashboard e conta transições de status; uma linha por edição de
-- telefone mudaria contagens que saem de lá. Aqui entra tudo, inclusive a mudança de status —
-- que aparece como o campo "status" alterado.
--
-- Escrita só pelo listener do Hibernate (auditoria/LogAtividadeHibernateListener). Como
-- historico_status e email_coelba, não tem endpoint de escrita.
--
-- usuario_id SEM FK: com FK, excluir um usuário passaria a falhar com 409 por causa do log, e o
-- nome fica copiado em usuario_nome justamente para o log continuar legível depois disso.
-- entidade_id também sem FK: o registro de uma EXCLUSAO aponta, por definição, para um id que
-- não existe mais.
CREATE TABLE log_atividade (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ocorrido_em     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    usuario_id      BIGINT,
    usuario_nome    VARCHAR(150),
    acao            VARCHAR(10)  NOT NULL,
    entidade_tipo   VARCHAR(30)  NOT NULL,
    entidade_id     BIGINT       NOT NULL,
    descricao       VARCHAR(300),
    alteracoes      JSONB        NOT NULL DEFAULT '[]'::jsonb,
    CONSTRAINT ck_log_atividade_acao CHECK (acao IN ('CRIACAO', 'EDICAO', 'EXCLUSAO')),
    CONSTRAINT ck_log_atividade_entidade_tipo CHECK (entidade_tipo IN
        ('CLIENTE', 'PENDENCIA', 'DEBITO', 'PROJETO', 'VISTORIA', 'UNIFICACAO', 'USUARIO'))
);

CREATE INDEX ix_log_atividade_ocorrido_em ON log_atividade (ocorrido_em DESC, id DESC);
CREATE INDEX ix_log_atividade_entidade ON log_atividade (entidade_tipo, entidade_id);
CREATE INDEX ix_log_atividade_usuario ON log_atividade (usuario_id, ocorrido_em DESC);
