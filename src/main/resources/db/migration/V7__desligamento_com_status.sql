-- O desligamento do medidor unificado deixa de ser um booleano e passa a ter status.
--
-- Motivo (esclarecido pelo usuário): terminada a instalação, confere-se se a unificação foi
-- feita; se sim, SOLICITA-SE o desligamento do medidor unificado e AGUARDA-SE o retorno. Um
-- booleano não consegue representar "solicitado, aguardando" — que é exatamente o estado onde
-- o caso se perde de vista. E quando a equipe de campo não realiza o desligamento, abre-se O.S.,
-- que é um desvio do caminho normal e também precisava de representação.
--
-- As datas permitem medir quanto tempo se espera o retorno, que é a informação que hoje ninguém
-- tem.
ALTER TABLE unificacao ADD COLUMN desligamento_status VARCHAR(20);
ALTER TABLE unificacao ADD COLUMN desligamento_solicitado_em DATE;
ALTER TABLE unificacao ADD COLUMN desligamento_concluido_em DATE;

-- Converte o dado existente: quem estava marcado como desligado virou CONCLUIDO.
UPDATE unificacao
SET desligamento_status = CASE WHEN desligamento THEN 'CONCLUIDO' ELSE 'NAO_SOLICITADO' END;

ALTER TABLE unificacao ALTER COLUMN desligamento_status SET NOT NULL;
ALTER TABLE unificacao ALTER COLUMN desligamento_status SET DEFAULT 'NAO_SOLICITADO';
ALTER TABLE unificacao ADD CONSTRAINT ck_unificacao_desligamento_status
    CHECK (desligamento_status IN ('NAO_SOLICITADO', 'SOLICITADO', 'OS_ABERTA', 'CONCLUIDO'));

-- Remove o booleano para não ficarem duas fontes de verdade sobre o mesmo fato.
ALTER TABLE unificacao DROP COLUMN desligamento;

CREATE INDEX idx_unificacao_desligamento_status ON unificacao (desligamento_status);
