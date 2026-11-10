package com.dentalclinic.financial.api;

import org.springframework.web.bind.annotation.*;
import com.dentalclinic.financial.application.CollectionCoordinator;
import com.dentalclinic.financial.api.dto.AppointmentCollectionResponse;
import com.dentalclinic.identity.infrastructure.security.CurrentUser;

@RestController
@RequestMapping("/api/appointments")
public class AppointmentCollectionController {
    private final CollectionCoordinator collections;
    private final CollectionRequestParser parser;
    public AppointmentCollectionController(CollectionCoordinator collections, CollectionRequestParser parser) {
        this.collections = collections;
        this.parser = parser;
    }

    @PostMapping("/{id}/collection")
    public AppointmentCollectionResponse collect(@PathVariable Long id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) String body) {
        return collections.collect(parser.parse(id, key, body), CurrentUser.require());
    }
}
