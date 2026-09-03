package com.conectsol.solarsync.auth;

import java.util.HashSet;
import java.util.Set;

import com.conectsol.solarsync.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "papel")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Papel extends BaseEntity {

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "nome", nullable = false, length = 50)
    private NomePapel nome;

    @Builder.Default
    @ManyToMany
    @JoinTable(
            name = "papel_permissao",
            joinColumns = @JoinColumn(name = "papel_id"),
            inverseJoinColumns = @JoinColumn(name = "permissao_id"))
    private Set<Permissao> permissoes = new HashSet<>();
}
