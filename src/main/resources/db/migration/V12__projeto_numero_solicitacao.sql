-- V12: número da solicitação que a Coelba devolve ao receber o projeto.
--
-- É a chave que liga o registro daqui ao e-mail diário de status da Coelba — sem ela, a leitura
-- automática do e-mail (seção 9 do CLAUDE.md) teria de casar por nome de cliente, que é
-- ambíguo. Serve também para o analista achar o projeto quando o retorno chega pelo número.
--
-- Sem UNIQUE de propósito: projeto reprovado e reenviado pode receber outro número, e a
-- importação da planilha traz o campo irregular ou ausente.

ALTER TABLE projeto ADD COLUMN numero_solicitacao VARCHAR(50);

CREATE INDEX idx_projeto_numero_solicitacao ON projeto (numero_solicitacao);
