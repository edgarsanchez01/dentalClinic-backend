package com.dentalclinic.identity.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.dentalclinic.clinic.domain.exception.InvalidClinicConfigurationException;
import com.dentalclinic.identity.domain.exception.AccountInactiveException;
import com.dentalclinic.identity.domain.exception.EmailAlreadyRegisteredException;
import com.dentalclinic.identity.domain.exception.ForbiddenOperationException;
import com.dentalclinic.identity.domain.exception.InstallationAlreadyConfiguredException;
import com.dentalclinic.identity.domain.exception.InstallationConcurrencyConflictException;
import com.dentalclinic.identity.domain.exception.InvalidCredentialsException;
import com.dentalclinic.identity.domain.exception.InvalidTokenException;
import com.dentalclinic.identity.domain.exception.MissingCurrentPasswordException;
import com.dentalclinic.identity.domain.exception.NeutralAuthenticationException;
import com.dentalclinic.identity.domain.exception.NotFoundException;
import com.dentalclinic.identity.domain.exception.PasswordExceedsHashingLimitException;
import com.dentalclinic.scheduling.domain.ScheduleConflictException;
import com.dentalclinic.scheduling.domain.exception.InvalidScheduleException;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Traducción de excepciones a respuestas de error.
 *
 * <p>El mapeo sigue Architecture.md §8.2. La tabla es normativa, y en ella se apoya también el
 * diseño de la matriz: 403 significa "identidad válida pero sin permiso por rol", mientras que
 * 404 significa "no existe o no es tuyo".
 *
 * <p>Las excepciones de dominio <strong>no</strong> conocen el protocolo HTTP: son Java plano con
 * un mensaje en español. El estado HTTP se decide aquí, en la capa de transporte. Así el dominio no
 * depende de Spring Web (Architecture.md §6.2).
 */
@RestControllerAdvice
public class RestExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(RestExceptionHandler.class);

    private static final String GENERIC_500 = "Ha ocurrido un error inesperado. Inténtalo de nuevo.";

    @ExceptionHandler({InvalidCredentialsException.class, AccountInactiveException.class,
            NeutralAuthenticationException.class})
    public ResponseEntity<ErrorResponse> handleUnauthorized(RuntimeException e, HttpServletRequest request) {
        return respond(HttpStatus.UNAUTHORIZED, e, request);
    }

    /** §8.2: "token inválido" se traduce a 401, también en activación y recuperación. */
    @ExceptionHandler(InvalidTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidToken(RuntimeException e, HttpServletRequest request) {
        return respond(HttpStatus.UNAUTHORIZED, e, request);
    }

    @ExceptionHandler({MissingCurrentPasswordException.class, PasswordExceedsHashingLimitException.class,
            InvalidClinicConfigurationException.class,
            com.dentalclinic.scheduling.domain.exception.InvalidScheduleException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(RuntimeException e, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, e, request);
    }

    /**
     * §8.2: "DataIntegrityViolation | 409 | Violación de constraint (con mensaje claro)".
     *
     * <p>{@link InstallationConcurrencyConflictException} también es 409: es la carrera concurrente
     * del onboarding (dos peticiones que pasaron la precondición a la vez), un conflicto de estado,
     * no una acción prohibida. La acción prohibida (instalación ya configurada) es 403.
     */
    @ExceptionHandler({EmailAlreadyRegisteredException.class,
            InstallationConcurrencyConflictException.class,
            com.dentalclinic.scheduling.domain.ScheduleConflictException.class,
            DataIntegrityViolationException.class})
    public ResponseEntity<ErrorResponse> handleConflict(RuntimeException e, HttpServletRequest request) {
        if (e instanceof DataIntegrityViolationException) {
            log.warn("Violación de integridad en {}: {}", request.getRequestURI(), e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(
                    HttpStatus.CONFLICT.value(),
                    HttpStatus.CONFLICT.getReasonPhrase(),
                    "El registro ya existe o entra en conflicto con los datos existentes.",
                    request.getRequestURI()));
        }
        return respond(HttpStatus.CONFLICT, e, request);
    }

    /** §8.2: "NotFound | 404 | No existe (o no es tuyo → 404)". */
    @ExceptionHandler({NotFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ErrorResponse> handleNotFound(Exception e, HttpServletRequest request) {
        String message = e instanceof NotFoundException && e.getMessage() != null
                ? e.getMessage()
                : "No encontramos el recurso solicitado.";
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(
                HttpStatus.NOT_FOUND.value(),
                HttpStatus.NOT_FOUND.getReasonPhrase(),
                message,
                request.getRequestURI()));
    }

    /**
     * 403 del onboarding: la instalación ya está configurada. No es 409 (eso es la carrera) ni 401
     * (no es un problema de credenciales): es una acción prohibida sobre el estado de la instalación.
     */
    @ExceptionHandler(InstallationAlreadyConfiguredException.class)
    public ResponseEntity<ErrorResponse> handleInstallationAlreadyConfigured(RuntimeException e,
                                                                             HttpServletRequest request) {
        return respond(HttpStatus.FORBIDDEN, e, request);
    }

    /**
     * §8.2: "AuthorizationDenied | 403". Operación prohibida con identidad válida y rol correcto
     * (p. ej. un DENTIST reservando una cita para otro profesional, A-15). No es 404: el recurso
     * existe, pero la acción está fuera del alcance del actor.
     */
    @ExceptionHandler(ForbiddenOperationException.class)
    public ResponseEntity<ErrorResponse> handleForbiddenOperation(RuntimeException e,
                                                                  HttpServletRequest request) {
        return respond(HttpStatus.FORBIDDEN, e, request);
    }

    /** §8.2: "AuthorizationDenied | 403 | Method security". */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleForbidden(Exception e, HttpServletRequest request) {
        log.warn("Acceso denegado por rol en {}", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse.of(
                HttpStatus.FORBIDDEN.value(),
                HttpStatus.FORBIDDEN.getReasonPhrase(),
                "No tienes permiso para realizar esta acción.",
                request.getRequestURI()));
    }

    /** §8.2: "MethodArgumentNotValid | 400 | Validación de DTO (con fieldErrors)". */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e,
                                                          HttpServletRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fieldError : e.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(fieldError.getField(),
                    fieldError.getDefaultMessage() == null ? "Valor no válido." : fieldError.getDefaultMessage());
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                "Revisa los datos del formulario.",
                request.getRequestURI(),
                fieldErrors));
    }

    /**
     * Método HTTP no soportado sobre una ruta existente: 405, nunca 500.
     *
     * <p>La ruta existe (por eso no es 404) pero no expone ese verbo; p. ej. {@code PUT}/{@code
     * DELETE} sobre las notas clínicas, que son append-only. Sin este handler, el catch-all lo
     * convertía en 500, ocultando que la operación simplemente no está permitida.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e,
                                                                HttpServletRequest request) {
        log.warn("Método {} no soportado en {}", e.getMethod(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(ErrorResponse.of(
                HttpStatus.METHOD_NOT_ALLOWED.value(),
                HttpStatus.METHOD_NOT_ALLOWED.getReasonPhrase(),
                "El método HTTP no está permitido para este recurso.",
                request.getRequestURI()));
    }

    /**
     * §8.2: "Genérica | 500 | Log WARN/ERROR + mensaje genérico. Nunca stack trace al cliente."
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e, HttpServletRequest request) {
        log.error("Fallo no controlado en {}", request.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErrorResponse.of(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(),
                GENERIC_500,
                request.getRequestURI()));
    }

    private ResponseEntity<ErrorResponse> respond(HttpStatus status, Exception e, HttpServletRequest request) {
        String message = e.getMessage() == null ? status.getReasonPhrase() : e.getMessage();
        return ResponseEntity.status(status).body(ErrorResponse.of(
                status.value(), status.getReasonPhrase(), message, request.getRequestURI()));
    }
}