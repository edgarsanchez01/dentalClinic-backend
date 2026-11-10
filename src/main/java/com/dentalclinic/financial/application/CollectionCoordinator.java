package com.dentalclinic.financial.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.dentalclinic.financial.api.dto.AppointmentCollectionResponse;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;

@Service
public class CollectionCoordinator {
    private final AppointmentCollectionService service;
    public CollectionCoordinator(AppointmentCollectionService service) { this.service = service; }

    /** The transaction must finish before returning a receipt or classifying a failure. */
    @Transactional(propagation = Propagation.NEVER)
    public AppointmentCollectionResponse collect(CollectionCommand command, AuthenticatedUser actor) {
        return service.collect(command, actor);
    }
}
