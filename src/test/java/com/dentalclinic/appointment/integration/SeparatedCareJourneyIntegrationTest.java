package com.dentalclinic.appointment.integration;

import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import static org.assertj.core.api.Assertions.*;

/** Replaces the retired checkout browser journey with a backend-only real HTTP journey.
 * Spring socket → PostgreSQL 16 real DDL, no network interception and no frontend process. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties={"spring.datasource.url=jdbc:tc:postgresql:16:///dentalclinic_ux05c_http"})
class SeparatedCareJourneyIntegrationTest extends AppointmentIntegrationTestSupport {
    @LocalServerPort private int port;
    private final HttpClient http=HttpClient.newHttpClient();

    @Test void clinicalCompletionAndAdministrativeCollectionsAreIndependentOverRealHttp() throws Exception {
        String doctorToken=loginToken(dentistUser), receptionToken=loginToken(receptionist), ownerToken=loginToken(owner);
        var created=send("POST","/api/appointments",createBody(patient.getId(),dentist.getId(),
                at(MARTES,9,0),at(MARTES,10,0)),ownerToken,"booking");
        assertThat(created.statusCode()).isEqualTo(201);
        long id=objectMapper.readTree(created.body()).path("id").asLong();
        String base="/api/appointments/"+id;
        jdbcTemplate.update("UPDATE appointment SET service_reference_price=500 WHERE id=?",id);
        assertThat(send("POST",base+"/checkout","{}",doctorToken,"retired").statusCode()).isEqualTo(403);
        assertThat(send("POST",base+"/complete","{\"expectedOccurrenceVersion\":1}",receptionToken,"not-clinical").statusCode()).isEqualTo(403);
        assertThat(send("POST",base+"/complete","{\"expectedOccurrenceVersion\":1}",ownerToken,"not-assigned").statusCode()).isEqualTo(403);
        var completed=send("POST",base+"/complete","{\"expectedOccurrenceVersion\":1}",doctorToken,"clinical");
        assertThat(completed.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(completed.body()).path("status").asText()).isEqualTo("COMPLETED");
        assertThat(countRows("charge")).isZero();
        assertThat(countRows("financial_movement")).isZero();
        var clinicalBefore=jdbcTemplate.queryForMap("SELECT * FROM appointment WHERE id=?",id);
        var context=send("GET",base+"/financial-context",null,receptionToken,null);
        assertThat(context.statusCode()).isEqualTo(200);
        String request=collectionBody(objectMapper.readTree(context.body()).path("financialContextToken").asText(),null,"200");
        assertThat(send("POST",base+"/collection",request,doctorToken,"forbidden").statusCode()).isEqualTo(403);
        var first=send("POST",base+"/collection",request,receptionToken,"partial");
        assertThat(first.statusCode()).isEqualTo(200);
        long charge=objectMapper.readTree(first.body()).at("/financialAtCollection/chargeId").asLong();
        context=send("GET",base+"/financial-context",null,receptionToken,null);
        String later=collectionBody(objectMapper.readTree(context.body()).path("financialContextToken").asText(),charge,"300");
        var paid=send("POST",base+"/collection",later,ownerToken,"later");
        assertThat(paid.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(paid.body()).at("/financialAtCollection/status").asText()).isEqualTo("PAGADO");
        var replay=send("POST",base+"/collection",request,receptionToken,"partial");
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(replay.body())).isEqualTo(objectMapper.readTree(first.body()));
        assertThat(jdbcTemplate.queryForMap("SELECT * FROM appointment WHERE id=?",id)).isEqualTo(clinicalBefore);
        assertThat(countRows("appointment_checkout")).isZero();
        assertThat(countRows("charge")).isEqualTo(1);
        assertThat(countRows("financial_movement")).isEqualTo(2);
        assertThat(countRows("patient_feedback")).isEqualTo(1);
        var queue=send("GET","/api/appointments/collection-queue?state=SETTLED",null,receptionToken,null);
        assertThat(queue.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(queue.body()).path("totalElements").asLong()).isEqualTo(1);
    }

    private HttpResponse<String> send(String method,String path,String body,String token,String key) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Authorization",bearer(token));
        if(key!=null) builder.header("Idempotency-Key",key);
        if(body!=null) builder.header("Content-Type","application/json");
        return http.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private String collectionBody(String token,Long charge,String amount) {
        return "{\"expectedOccurrenceVersion\":1,\"financialContextToken\":\""+token+"\",\"charge\":{\"action\":\""
                +(charge==null?"CREATE_FROM_APPOINTMENT":"REUSE")+"\","+(charge==null?"":"\"chargeId\":"+charge+",")
                +"\"expectedTotal\":500,\"confirmed\":true},\"payment\":{\"action\":\"REGISTER\",\"method\":\"CASH\",\"amount\":"+amount+"}}";
    }
}
