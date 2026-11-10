package com.dentalclinic.medicalintake;

import static org.assertj.core.api.Assertions.*;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import com.dentalclinic.medicalintake.domain.*;
import com.dentalclinic.medicalintake.domain.IntakeAnswers.*;
import com.dentalclinic.patient.domain.IntakePersonalData;

class IntakeValidationTest {
    private final LocalDate today = LocalDate.of(2026,10,6);
    private IntakePersonalData data(LocalDate birth) {
        return new IntakePersonalData("P","Test",birth,"555",true,"C","Amistad","556",null,null,null);
    }
    @Test void exactEighteenthBirthdayIsAdultAndYesterdayIsMinor() {
        assertThat(IntakeValidation.isMinor(data(today.minusYears(18)), today)).isFalse();
        assertThat(IntakeValidation.isMinor(data(today.minusYears(18).plusDays(1)), today)).isTrue();
        assertThatCode(() -> IntakeValidation.personal(data(today.minusYears(18)),today,true)).doesNotThrowAnyException();
    }
    @Test void futureAndMissingBirthAndMissingEmergencyAreRejectedOnCompletion() {
        assertThatThrownBy(() -> IntakeValidation.personal(data(today.plusDays(1)),today,false)).isInstanceOf(IntakeException.class);
        assertThatThrownBy(() -> IntakeValidation.personal(data(null),today,true)).isInstanceOf(IntakeException.class);
        assertThatThrownBy(() -> IntakeValidation.personal(new IntakePersonalData("P","Test",today.minusYears(20),
                "555",true,null,null,null,null,null,null),today,true)).isInstanceOf(IntakeException.class);
    }
    @Test void detailsNeverRequiredAndNullByteAndOversizeRejected() {
        for (Answer answer : Answer.values()) {
            var q = new Question(answer, null);
            assertThatCode(() -> IntakeValidation.medical(new Medical(q,q,q,q,q,q),true)).doesNotThrowAnyException();
        }
        var nul = new Question(Answer.YES,"synthetic\0data");
        assertThatThrownBy(() -> IntakeValidation.medical(new Medical(nul,nul,nul,nul,nul,nul),false)).isInstanceOf(IntakeException.class);
        var q = new Question(Answer.NO,null);
        assertThatThrownBy(() -> IntakeValidation.dental(new Dental(q,q,q,q,"x".repeat(1001)),true)).isInstanceOf(IntakeException.class);
    }
    @Test void sensitiveRecordsDoNotExposeValuesInToString() {
        String secret="synthetic-secret";
        assertThat(new Question(Answer.YES,secret).toString()).doesNotContain(secret);
        assertThat(data(today).toString()).doesNotContain("555");
    }
}
