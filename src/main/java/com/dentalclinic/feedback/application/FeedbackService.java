package com.dentalclinic.feedback.application;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.dentalclinic.appointment.application.AppointmentCommunicationPlanner;
import com.dentalclinic.appointment.application.ConsultationCompleted;
import com.dentalclinic.appointment.application.OutboundEmail;
import com.dentalclinic.feedback.domain.PatientFeedback;
import com.dentalclinic.feedback.infrastructure.FeedbackRepository;
import com.dentalclinic.feedback.infrastructure.FeedbackTokens;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.exception.ForbiddenOperationException;
import com.dentalclinic.identity.domain.exception.NotFoundException;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;
import com.dentalclinic.identity.infrastructure.token.TokenHasher;

@Service
public class FeedbackService {
    private final FeedbackRepository repository;
    private final FeedbackTokens tokens;
    private final TokenHasher hasher;
    private final AppointmentCommunicationPlanner communications;
    private final Clock clock;
    private final String publicUrl;

    public FeedbackService(FeedbackRepository repository, FeedbackTokens tokens, TokenHasher hasher,
            AppointmentCommunicationPlanner communications, Clock clock,
            @Value("${dentalclinic.feedback.public-base-url:}") String publicUrl) {
        this.repository = repository;
        this.tokens = tokens;
        this.hasher = hasher;
        this.communications = communications;
        this.clock = clock;
        this.publicUrl = publicUrl.replaceAll("/+$", "");
        if (!this.publicUrl.isEmpty()) {
            var uri = java.net.URI.create(this.publicUrl);
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme())
                        && java.util.Set.of("localhost", "127.0.0.1").contains(uri.getHost())))) {
                throw new IllegalArgumentException("La URL pública de feedback debe usar HTTPS (HTTP solo localhost).");
            }
        }
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void request(ConsultationCompleted event) {
        if (!repository.completed(event.appointmentId())) throw new IllegalArgumentException("La consulta no está completada.");
        // El productor mantiene el lock de appointment hasta el commit; UNIQUE cubre además carreras.
        if (repository.findByAppointmentId(event.appointmentId()).isPresent()) return;
        Instant now = event.completedAt().truncatedTo(ChronoUnit.MICROS);
        String hash = hasher.hash(tokens.token(event.appointmentId(), now));
        repository.saveAndFlush(PatientFeedback.request(event.appointmentId(), hash, now));
        communications.feedbackRequested(event);
    }

    @Transactional(readOnly = true)
    public String state(String token) {
        return find(token, false).filter(f -> repository.completed(f.getAppointmentId()))
                .map(f -> f.linkState(clock.instant())).orElse("INVALID");
    }

    @Transactional
    public String respond(String token, int rating, String comment) {
        if (rating < 1 || rating > 5 || (comment != null && comment.length() > 2000)) {
            throw new IllegalArgumentException("Selecciona de 1 a 5 estrellas; comentario máximo de 2000 caracteres.");
        }
        var optional = find(token, true);
        if (optional.isEmpty()) return "INVALID";
        var feedback = optional.orElseThrow();
        if (!repository.completed(feedback.getAppointmentId())) return "INVALID";
        Instant now = clock.instant(); // Después del lock: respeta exactamente el borde de expiración.
        String state = feedback.linkState(now);
        if (!"AVAILABLE".equals(state)) return state;
        feedback.respond(rating, comment, now);
        return "RESPONDED";
    }

    private java.util.Optional<PatientFeedback> find(String token, boolean lock) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return java.util.Optional.empty();
        String hash = hasher.hash(token);
        return lock ? repository.lockToken(hash) : repository.findByTokenHash(hash);
    }

    @Transactional(readOnly = true)
    public OutboundEmail invitationEmail(Long appointmentId, String address) {
        var feedback = repository.findByAppointmentId(appointmentId).orElseThrow();
        if (publicUrl.isBlank()) throw new IllegalStateException("Configura FEEDBACK_PUBLIC_BASE_URL antes de enviar.");
        String token = tokens.token(appointmentId, feedback.getCreatedAt());
        if (!hasher.hash(token).equals(feedback.getTokenHash())) throw new IllegalStateException("Clave de feedback cambiada.");
        return new OutboundEmail(address, "¿Cómo fue tu atención?",
                "Gracias por tu visita. Te invitamos a valorar la atención recibida, de 1 a 5 estrellas.\n\n"
                + publicUrl + "/feedback/" + token + "\n\n"
                + "El enlace es personal, permite una sola respuesta y tiene una vigencia de 7 días desde la invitación.\n"
                + "Tu comentario es opcional. La reseña es privada y solo podrá consultarla el personal autorizado.\n"
                + "No compartas el enlace. Por favor, no incluyas información clínica sensible en el comentario.");
    }

    @Transactional(readOnly = true)
    public boolean sendable(Long appointmentId) {
        return repository.findByAppointmentId(appointmentId)
                .filter(f -> "AVAILABLE".equals(f.linkState(clock.instant())))
                .filter(f -> repository.completed(appointmentId)).isPresent();
    }

    @Transactional(readOnly = true)
    public Reviews reviews(AuthenticatedUser actor, int page, int size) {
        authorize(actor);
        var summary = repository.summary(actor.role() == Role.OWNER, actor.userId());
        return new Reviews(repository.reviews(actor.role() == Role.OWNER, actor.userId(),
                PageRequest.of(page, Math.min(size, 50))).map(Review::from), summary.getCount(), summary.getAverage());
    }

    @Transactional(readOnly = true)
    public Review review(AuthenticatedUser actor, Long id) {
        authorize(actor);
        return repository.review(id, actor.role() == Role.OWNER, actor.userId()).map(Review::from)
                .orElseThrow(NotFoundException::new);
    }

    private void authorize(AuthenticatedUser actor) {
        if (actor.role() != Role.OWNER && actor.role() != Role.DENTIST)
            throw new ForbiddenOperationException("No tienes acceso a reseñas.");
    }

    public record Review(Long id, int rating, String comment, Instant respondedAt) {
        static Review from(PatientFeedback f) { return new Review(f.getId(), f.getRating(), f.getComment(), f.getRespondedAt()); }
    }
    public record Reviews(Page<Review> reviews, long count, Double average) { }
}
