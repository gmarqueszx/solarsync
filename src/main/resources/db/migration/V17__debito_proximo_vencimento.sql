-- Débito futuro do cliente (22/09/2026).
--
-- O problema levantado pela equipe: o cliente pode estar quitado hoje e ter a próxima conta
-- vencendo amanhã. O projeto encaminhado nessa véspera volta reprovado da Coelba, porque quando
-- ela for analisar já existe débito. A consulta na agência virtual mostra a data; faltava onde
-- guardá-la.
--
-- Fica no mesmo registro de débito, e não numa tabela de vencimentos: é dado da última consulta
-- ("quitado, e o próximo vence em"), exatamente como ultima_consulta_em e quitado_em. Uma tabela
-- de faturas seria contabilidade da Coelba, que não é o que este sistema acompanha.
--
-- Nulável de propósito: "não informado" é o caso normal — nem toda consulta revela a próxima
-- data, e inventar uma seria pior que não ter.
ALTER TABLE debito
    ADD COLUMN proximo_vencimento DATE;

COMMENT ON COLUMN debito.proximo_vencimento IS
    'Vencimento da próxima conta, informado na consulta que constatou a quitação. Quando falta um '
    'dia ou menos para ele, encaminhar o projeto à Coelba é recusado com 409 '
    'PROXIMO_DEBITO_A_VENCER (ProjetoService).';
