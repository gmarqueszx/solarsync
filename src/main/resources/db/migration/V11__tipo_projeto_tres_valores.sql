-- V11: só existem três subtipos de projeto na operação real (confirmado com o usuário):
-- Projeto Inicial, Ampliação de Projeto Existente e Correção de Projeto.
--
-- Os seis valores da V1 vieram das abas da planilha, que separava por caso operacional
-- ("uma placa a mais", "mudança de inversor 5kW") e não por tipo de projeto. O frontend já
-- expunha apenas dois deles no cadastro; os outros quatro só existiam em registros legados.

ALTER TABLE projeto DROP CONSTRAINT ck_projeto_tipo;

UPDATE projeto SET tipo_projeto = CASE tipo_projeto
    WHEN 'PADRAO'                   THEN 'PROJETO_INICIAL'
    WHEN 'AUMENTO_POTENCIA'         THEN 'AMPLIACAO'
    WHEN 'PROJETO_UMA_PLACA_A_MAIS' THEN 'AMPLIACAO'
    WHEN 'MUDANCA_INVERSOR'         THEN 'CORRECAO'
    WHEN 'INVERSORES_SEPARADOS'     THEN 'CORRECAO'
    ELSE tipo_projeto
END;

ALTER TABLE projeto ADD CONSTRAINT ck_projeto_tipo
    CHECK (tipo_projeto IN ('PROJETO_INICIAL', 'AMPLIACAO', 'CORRECAO'));
