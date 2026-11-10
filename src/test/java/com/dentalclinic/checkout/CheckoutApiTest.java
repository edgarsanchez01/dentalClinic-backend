package com.dentalclinic.checkout;

import java.util.List;
import org.junit.jupiter.api.Test;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;
import com.dentalclinic.identity.domain.exception.ForbiddenOperationException;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** UX-05C: retired for ALL roles, not just hidden in a client. */
class CheckoutApiTest extends CheckoutFlowSupport {
    @Test void httpAndApplicationNeverExecuteEvenWithAValidCommand() throws Exception {
        var body = body(token(), null, "200");
        var command = parser.parse(scheduled, "retired", body);
        for (var actor : List.of(doctor, owner, new AuthenticatedUser(receptionId, Role.RECEPTIONIST),
                new AuthenticatedUser(jdbc.queryForObject("SELECT user_id FROM patient WHERE id=?", Long.class, patientId), Role.PATIENT))) {
            postBody("retired", body, actor).andExpect(status().isForbidden())
                    .andExpect(header().string("Cache-Control", "no-store"));
            assertThatThrownBy(() -> coordinator.checkout(command, actor)).isInstanceOf(ForbiddenOperationException.class);
        }
        assertNoCheckout();
        assertThat(count("financial_movement")).isZero();
        assertThat(count("http_idempotency")).isZero();
    }

    @Test void assignedOwnerAlsoCannotUseRetiredRouteAndAnonymousStillNeedsAuthentication() throws Exception {
        jdbc.update("UPDATE appointment SET dentist_id=(SELECT id FROM dentist WHERE user_id=?) WHERE id=?", ownerId, scheduled);
        var body = body(contexts.contextFor(scheduled, owner).financialContextToken(), null, null);
        postBody("owner-retired", body, owner).andExpect(status().isForbidden());
        mvc.perform(post(path(scheduled)).contentType("application/json").content(body))
                .andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control", "no-store"));
        assertNoCheckout();
    }
}
