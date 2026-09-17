package com.conectsol.solarsync.auth;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PapelRepository extends JpaRepository<Papel, Long> {

    Optional<Papel> findByNome(NomePapel nome);
}
