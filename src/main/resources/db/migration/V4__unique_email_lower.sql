-- uk_usuario_email era case-sensitive (comportamento padrão do Postgres). Como o Google devolve
-- o e-mail sempre em minúsculas, um usuário cadastrado como 'Joao@conectsol.com' nunca
-- conseguiria entrar pelo Google: findByEmail('joao@conectsol.com') não acharia nada.
--
-- A aplicação normaliza (trim + lowercase) na entrada; o índice funcional abaixo garante a
-- unicidade também no banco, e substitui a constraint antiga (qualquer duplicata exata também
-- é duplicata de lower(email), então nada se perde).
UPDATE usuario SET email = lower(trim(email));

ALTER TABLE usuario DROP CONSTRAINT uk_usuario_email;

CREATE UNIQUE INDEX uk_usuario_email ON usuario (lower(email));
