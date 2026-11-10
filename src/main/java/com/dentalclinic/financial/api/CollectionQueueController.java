package com.dentalclinic.financial.api;

import java.time.Instant;
import org.springframework.web.bind.annotation.*;
import com.dentalclinic.financial.api.dto.CollectionQueueResponse;
import com.dentalclinic.financial.api.dto.CollectionQueueResponse.State;
import com.dentalclinic.financial.application.CollectionQueueService;
import com.dentalclinic.identity.infrastructure.security.CurrentUser;

@RestController
@RequestMapping("/api/appointments/collection-queue")
public class CollectionQueueController {
    private final CollectionQueueService queue;
    public CollectionQueueController(CollectionQueueService queue) { this.queue = queue; }

    @GetMapping
    public CollectionQueueResponse list(@RequestParam(required = false) Long dentistId,
            @RequestParam(required = false) Long patientId, @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to, @RequestParam(required = false) State state,
            @RequestParam(defaultValue = "false") boolean includeSettled,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return queue.list(dentistId, patientId, from, to, state, includeSettled, page, size, CurrentUser.require());
    }
}
