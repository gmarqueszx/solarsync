package com.conectsol.solarsync.auth;

import java.util.HashSet;
import java.util.Set;

import com.conectsol.solarsync.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "usuario")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Usuario extends BaseEntity {

    @NotBlank
    @Column(name = "nome", nullable = false, length = 150)
    private String nome;

    @NotBlank
    @Email
    @Column(name = "email", nullable = false, length = 150)
    private String email;

    /**
     * Normaliza na escrita para casar com o índice único funcional em {@code lower(email)}
     * (migration V4). Sem isso, um usuário cadastrado como {@code Joao@conectsol.com} nunca
     * conseguiria entrar pelo Google, que devolve o e-mail sempre em minúsculas.
     * <p>
     * Escrito à mão de propósito: o Lombok não gera o setter quando ele já existe, então não
     * há caminho de escrita que escape da normalização.
     */
    public void setEmail(String email) {
        this.email = normalizarEmail(email);
    }

    /** Use também ao consultar por e-mail, para bater com o que foi gravado. */
    public static String normalizarEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }

    /**
     * O {@code @Builder} e o {@code @AllArgsConstructor} do Lombok contornam o setter, então a
     * normalização é reforçada aqui: nenhum caminho de persistência escapa.
     */
    @PrePersist
    @PreUpdate
    private void normalizarAntesDeGravar() {
        this.email = normalizarEmail(this.email);
    }

    @Column(name = "senha_hash", length = 255)
    private String senhaHash;

    @Builder.Default
    @Column(name = "ativo", nullable = false)
    private boolean ativo = true;

    @Builder.Default
    @ManyToMany
    @JoinTable(
            name = "usuario_papel",
            joinColumns = @JoinColumn(name = "usuario_id"),
            inverseJoinColumns = @JoinColumn(name = "papel_id"))
    private Set<Papel> papeis = new HashSet<>();
}
