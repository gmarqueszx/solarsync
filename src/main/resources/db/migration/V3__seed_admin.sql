-- Único usuário semeado. Os demais (Igor, Ivan, Larissa, Camila, Nycole...) são cadastrados
-- por ele via /api/usuarios, para não haver e-mail chutado em migration nem necessidade de um
-- deploy a cada entrada/saída de pessoa.
--
-- senha_hash fica NULL de propósito: o primeiro acesso é pelo Google Workspace. Nenhum hash de
-- senha entra no repositório (checklist item 6 do CLAUDE.md). Se precisar de acesso por senha,
-- use POST /api/usuarios/{id}/senha depois de entrar pelo Google.
INSERT INTO usuario (nome, email, ativo)
VALUES ('João Gabriel', 'joaogabriel@conectsol.com', TRUE);

INSERT INTO usuario_papel (usuario_id, papel_id)
SELECT u.id, p.id
FROM usuario u, papel p
WHERE u.email = 'joaogabriel@conectsol.com'
  AND p.nome = 'ADMINISTRADOR';
