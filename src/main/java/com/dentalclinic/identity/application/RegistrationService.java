package com.dentalclinic.identity.application;

import java.time.LocalDate;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.identity.domain.EmailCanonicalizer;
import com.dentalclinic.identity.domain.PasswordPolicy;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.User;
import com.dentalclinic.identity.domain.exception.EmailAlreadyRegisteredException;
import com.dentalclinic.identity.infrastructure.repository.UserRepository;
import com.dentalclinic.patient.application.PatientService;
import com.dentalclinic.patient.application.PatientService.PatientData;

/**
 * Registro publico de paciente (RF-IA-05/06, §6.5.1).
 *
 * <p>Crea, en <strong>una sola transaccion</strong>, la cuenta {@code User(role=PATIENT,
 * active=true)}, su {@code Patient} y su sesión autenticada. Si algo falla, la transacción
 * revierte los tres: no puede quedar una cuenta parcial ni una sesión de un alta fallida.
 *
 * <p>El rol <strong>nunca</strong> procede del cuerpo de la peticion: aqui se fija a
 * {@link Role#PATIENT}. No se crea verificacion de email (no existe en el producto).
 *
 * <p>La asociacion cuenta-paciente se establece por el {@code userId} recien creado, en la misma
 * transaccion. <strong>No</strong> se infiere por email, nombre ni telefono (AD-22, P-08): vincular
 * una cuenta nueva a un {@code Patient} preexistente es una brecha diferida documentada en PRD §37.
 */
@Service
public class RegistrationService {

    private final UserRepository userRepository;
    private final PatientService patientService;
    private final PasswordEncoder passwordEncoder;
    private final SessionService sessionService;

    public RegistrationService(UserRepository userRepository,
                               PatientService patientService,
                                PasswordEncoder passwordEncoder,
                                SessionService sessionService) {
        this.userRepository = userRepository;
        this.patientService = patientService;
        this.passwordEncoder = passwordEncoder;
        this.sessionService = sessionService;
    }

    @Transactional
    public IssuedSession register(RegistrationData data) {
        String email = EmailCanonicalizer.canonicalize(data.email());
        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyRegisteredException();
        }
        PasswordPolicy.requireWithinHashingLimit(data.password());

        User user = userRepository.saveAndFlush(
                User.create(email, passwordEncoder.encode(data.password()), Role.PATIENT));
        var patient = patientService.createForUser(user.getId(), new PatientData(
                data.firstName(), data.lastName(), null, data.phone(), data.birthDate(), null));
        patientService.requireMedicalOnboarding(patient.getId());

        // Misma emisión que login; un fallo de sesión también revierte User y Patient.
        return sessionService.openRegistrationSession(user);
    }

    /** Datos de entrada del registro publico. El rol no aparece: se fija en el servicio. */
    public record RegistrationData(String email, String password, String firstName, String lastName,
                                   String phone, LocalDate birthDate) {
    }

}
