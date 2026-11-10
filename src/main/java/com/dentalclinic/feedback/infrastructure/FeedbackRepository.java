package com.dentalclinic.feedback.infrastructure;

import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.dentalclinic.feedback.domain.PatientFeedback;

public interface FeedbackRepository extends JpaRepository<PatientFeedback, Long> {
    Optional<PatientFeedback> findByAppointmentId(Long appointmentId);
    Optional<PatientFeedback> findByTokenHash(String hash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from PatientFeedback f where f.tokenHash = :hash")
    Optional<PatientFeedback> lockToken(@Param("hash") String hash);

    // Ownership siempre contra la cita/dentista persistidos, nunca contra IDs del cliente.
    String SCOPE = " from PatientFeedback f join Appointment a on a.id = f.appointmentId "
            + "join Dentist d on d.id = a.dentistId where a.status = 'COMPLETED' "
            + "and f.status = 'RESPONDED' and (:global = true or d.userId = :userId) ";

    @Query("select f" + SCOPE + "order by f.respondedAt desc, f.id desc")
    Page<PatientFeedback> reviews(@Param("global") boolean global, @Param("userId") Long userId, Pageable pageable);

    @Query("select count(f) as count, avg(f.rating) as average" + SCOPE)
    Summary summary(@Param("global") boolean global, @Param("userId") Long userId);

    @Query("select f" + SCOPE + "and f.id = :id")
    Optional<PatientFeedback> review(@Param("id") Long id, @Param("global") boolean global, @Param("userId") Long userId);

    @Query("select count(a) > 0 from Appointment a where a.id = :id and a.status = 'COMPLETED'")
    boolean completed(@Param("id") Long appointmentId);

    interface Summary { long getCount(); Double getAverage(); }
}
