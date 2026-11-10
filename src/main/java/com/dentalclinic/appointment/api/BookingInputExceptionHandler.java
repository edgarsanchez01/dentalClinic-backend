package com.dentalclinic.appointment.api;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import jakarta.servlet.http.HttpServletRequest;

import com.dentalclinic.identity.api.ErrorResponse;
import com.dentalclinic.scheduling.api.AvailabilityController;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {AppointmentController.class, AvailabilityController.class})
public class BookingInputExceptionHandler {
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> invalid(Exception error, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(400, "Bad Request",
                "Revisa el formato de los datos de la reserva.", request.getRequestURI()));
    }
}
