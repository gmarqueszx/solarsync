package com.conectsol.solarsync.common.web;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.conectsol.solarsync.common.exception.ClienteComDebitoException;
import com.conectsol.solarsync.common.exception.CredenciaisInvalidasException;
import com.conectsol.solarsync.common.exception.DebitoNaoConsultadoException;
import com.conectsol.solarsync.common.exception.ProjetoSemInstalacaoException;
import com.conectsol.solarsync.common.exception.TransicaoStatusInvalidaException;
import com.conectsol.solarsync.common.exception.UnificacaoNaoFeitaException;
import com.conectsol.solarsync.common.exception.UsuarioNaoAutorizadoException;

import jakarta.persistence.EntityNotFoundException;

/**
 * Formato único de erro da API, em {@link ProblemDetail} (RFC 9457), estendido com a
 * propriedade {@code codigo} — string estável que o frontend usa para ramificar sem depender
 * do texto da mensagem.
 * <p>
 * Cuidado ao mexer: erros de autenticação/autorização têm <b>dois</b> caminhos. O
 * {@code @PreAuthorize} negado lança {@code AuthorizationDeniedException} dentro do
 * interceptor de método e chega aqui; já a falta de token é tratada no filtro
 * ({@code ExceptionTranslationFilter}) e nunca passaria por este advice. Por isso o
 * {@code SecurityConfig} registra entry point e denied handler que delegam ao
 * {@code handlerExceptionResolver}, fazendo os dois caminhos caírem aqui e devolverem o
 * mesmo formato.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    public record ErroCampo(String campo, String mensagem) {
    }

    static ProblemDetail problema(HttpStatus status, String codigo, String detalhe) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(status, detalhe);
        problema.setProperty("codigo", codigo);
        return problema;
    }

    @ExceptionHandler(EntityNotFoundException.class)
    ProblemDetail naoEncontrado(EntityNotFoundException excecao) {
        return problema(HttpStatus.NOT_FOUND, "RECURSO_NAO_ENCONTRADO", excecao.getMessage());
    }

    @ExceptionHandler(TransicaoStatusInvalidaException.class)
    ProblemDetail transicaoInvalida(TransicaoStatusInvalidaException excecao) {
        return problema(HttpStatus.CONFLICT, "TRANSICAO_INVALIDA", excecao.getMessage());
    }

    @ExceptionHandler(ClienteComDebitoException.class)
    ProblemDetail clienteComDebito(ClienteComDebitoException excecao) {
        return problema(HttpStatus.CONFLICT, "CLIENTE_COM_DEBITO", excecao.getMessage());
    }

    @ExceptionHandler(DebitoNaoConsultadoException.class)
    ProblemDetail debitoNaoConsultado(DebitoNaoConsultadoException excecao) {
        return problema(HttpStatus.CONFLICT, "DEBITO_NAO_CONSULTADO", excecao.getMessage());
    }

    @ExceptionHandler(UnificacaoNaoFeitaException.class)
    ProblemDetail unificacaoNaoFeita(UnificacaoNaoFeitaException excecao) {
        return problema(HttpStatus.CONFLICT, "UNIFICACAO_NAO_FEITA", excecao.getMessage());
    }

    @ExceptionHandler(ProjetoSemInstalacaoException.class)
    ProblemDetail projetoSemInstalacao(ProjetoSemInstalacaoException excecao) {
        return problema(HttpStatus.CONFLICT, "PROJETO_SEM_INSTALACAO", excecao.getMessage());
    }

    @ExceptionHandler(CredenciaisInvalidasException.class)
    ProblemDetail credenciaisInvalidas(CredenciaisInvalidasException excecao) {
        return problema(HttpStatus.UNAUTHORIZED, "CREDENCIAIS_INVALIDAS", excecao.getMessage());
    }

    @ExceptionHandler(UsuarioNaoAutorizadoException.class)
    ProblemDetail usuarioNaoAutorizado(UsuarioNaoAutorizadoException excecao) {
        return problema(HttpStatus.UNAUTHORIZED, "USUARIO_NAO_AUTORIZADO", excecao.getMessage());
    }

    /** Falta de token ou token inválido, vindo do filtro via handlerExceptionResolver. */
    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail naoAutenticado(AuthenticationException excecao) {
        return problema(HttpStatus.UNAUTHORIZED, "NAO_AUTENTICADO", "Autenticação necessária");
    }

    /** Cobre também AuthorizationDeniedException, que estende AccessDeniedException. */
    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail acessoNegado(AccessDeniedException excecao) {
        return problema(HttpStatus.FORBIDDEN, "ACESSO_NEGADO",
                "Seu papel não permite esta operação");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail conflitoDeDados(DataIntegrityViolationException excecao) {
        log.warn("Violação de integridade", excecao);
        return problema(HttpStatus.CONFLICT, "CONFLITO_DADOS",
                "A operação conflita com dados existentes");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail erroInterno(Exception excecao) {
        log.error("Erro não tratado", excecao);
        return problema(HttpStatus.INTERNAL_SERVER_ERROR, "ERRO_INTERNO",
                "Erro interno. Consulte o log do servidor.");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException excecao, HttpHeaders cabecalhos,
            HttpStatusCode status, WebRequest requisicao) {

        List<ErroCampo> erros = excecao.getBindingResult().getFieldErrors().stream()
                .map(erro -> new ErroCampo(erro.getField(), erro.getDefaultMessage()))
                .toList();

        ProblemDetail problema = problema(HttpStatus.BAD_REQUEST, "VALIDACAO",
                "Requisição com campos inválidos");
        problema.setProperty("erros", erros);
        return ResponseEntity.badRequest().body(problema);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException excecao, HttpHeaders cabecalhos,
            HttpStatusCode status, WebRequest requisicao) {

        return ResponseEntity.badRequest().body(
                problema(HttpStatus.BAD_REQUEST, "VALIDACAO", "Parâmetros inválidos"));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException excecao, HttpHeaders cabecalhos,
            HttpStatusCode status, WebRequest requisicao) {

        return ResponseEntity.badRequest().body(problema(HttpStatus.BAD_REQUEST,
                "REQUISICAO_INVALIDA", "Corpo da requisição ilegível ou com valor não aceito"));
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            org.springframework.beans.TypeMismatchException excecao, HttpHeaders cabecalhos,
            HttpStatusCode status, WebRequest requisicao) {

        String detalhe = excecao instanceof MethodArgumentTypeMismatchException erro
                ? "Valor inválido para o parâmetro '%s'".formatted(erro.getName())
                : "Parâmetro com tipo inválido";
        return ResponseEntity.badRequest()
                .body(problema(HttpStatus.BAD_REQUEST, "REQUISICAO_INVALIDA", detalhe));
    }
}
