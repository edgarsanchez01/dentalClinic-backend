package com.dentalclinic.odontogram.api;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import com.dentalclinic.odontogram.application.OdontogramService.ToothData;
import com.dentalclinic.odontogram.domain.ToothCondition;
import com.dentalclinic.odontogram.infrastructure.repository.OdontogramEventRepository;

/** Explain real queries with isolated synthetic skew; no index changes or production measurements. */
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:16:///dentalclinic_odontogram_e2_explain?TC_TMPFS=/var/lib/postgresql/data:rw"
})
class OdontogramHistoryExplainTest extends OdontogramHistoryTestSupport {
    @Test void indicesExistentesAtiendenHistorialYDiffSinIndiceAdicional() {
        Long own = createTooth("Propias", List.of(), null);
        var other = seedPatientWithoutChannel();
        Long tooth = odontogram.create(other.getId(), new ToothData(21, ToothCondition.HEALTHY, "Otra", List.of()), actor()).record().getId();
        jdbcTemplate.update("INSERT INTO tooth_notes_history(tooth_record_id,previous_notes,new_notes,actor_user_id,changed_at)"
                + " SELECT ?,'Anterior','Nota sintetica '||n,?,now() FROM generate_series(1,5000) n", tooth, dentistUser.getId());
        jdbcTemplate.update("INSERT INTO treatment(patient_id,description,created_at) SELECT ?,'Sintetico '||n,now()"
                + " FROM generate_series(1,3000) n", other.getId());
        jdbcTemplate.update("INSERT INTO treatment_tooth(treatment_id,tooth_record_id,created_at) SELECT id,?,now()"
                + " FROM treatment WHERE patient_id=?", tooth, other.getId());
        for (String table : List.of("tooth_record", "tooth_notes_history", "treatment_tooth")) jdbcTemplate.execute("ANALYZE " + table);
        var diff = String.join("\n", jdbcTemplate.queryForList("EXPLAIN (ANALYZE,BUFFERS) SELECT * FROM treatment_tooth WHERE tooth_record_id=?", String.class, own));
        var timeline = String.join("\n", jdbcTemplate.queryForList("EXPLAIN (ANALYZE,BUFFERS) SELECT * FROM ("
                + OdontogramEventRepository.STREAM_SQL + ") e WHERE patient_id=?"
                + " ORDER BY changed_at DESC,source_rank DESC,id DESC LIMIT 20", String.class, patient.getId()));
        var parentLookup = String.join("\n", jdbcTemplate.queryForList("EXPLAIN (ANALYZE,BUFFERS) SELECT *"
                + " FROM tooth_notes_history WHERE tooth_record_id=? ORDER BY changed_at,id", String.class, own));
        System.out.println("E2 EXPLAIN treatment diff:\n" + diff);
        System.out.println("E2 EXPLAIN history union:\n" + timeline);
        System.out.println("E2 EXPLAIN history parent lookup:\n" + parentLookup);
        assertThat(diff).contains("treatment_tooth_record_idx");
        // The optimizer can legitimately use a hash join/seq scan for a small skewed UNION.
        // Record its actual plan; do not force it to choose an index or add a redundant one.
        assertThat(timeline).contains("Execution Time:");
        assertThat(parentLookup).contains("tooth_notes_history_record_idx");
        assertThat(jdbcTemplate.queryForList("SELECT indexname FROM pg_indexes WHERE tablename='treatment_tooth'", String.class))
                .containsExactlyInAnyOrder("treatment_tooth_pkey", "treatment_tooth_key", "treatment_tooth_record_idx");
    }
}
