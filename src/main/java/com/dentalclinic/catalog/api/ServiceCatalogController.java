package com.dentalclinic.catalog.api;

import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;

import com.dentalclinic.catalog.api.dto.ServiceActiveRequest;
import com.dentalclinic.catalog.api.dto.ServiceResponse;
import com.dentalclinic.catalog.api.dto.ServiceWriteRequest;
import com.dentalclinic.catalog.application.ServiceCatalogService;
import com.dentalclinic.catalog.application.ServiceCatalogService.ServiceData;

@RestController
@RequestMapping("/api/services")
public class ServiceCatalogController {
    private final ServiceCatalogService service;

    public ServiceCatalogController(ServiceCatalogService service) { this.service = service; }

    @GetMapping
    public List<ServiceResponse> list(@RequestParam(name = "active", required = false) Boolean active) {
        return service.list(active).stream().map(ServiceResponse::from).toList();
    }

    @GetMapping("/{id}")
    public ServiceResponse get(@PathVariable("id") Long id) { return ServiceResponse.from(service.get(id)); }

    @PostMapping
    public ResponseEntity<ServiceResponse> create(@Valid @RequestBody ServiceWriteRequest request) {
        ServiceResponse created = ServiceResponse.from(service.create(data(request)));
        return ResponseEntity.created(URI.create("/api/services/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public ServiceResponse update(@PathVariable("id") Long id, @Valid @RequestBody ServiceWriteRequest request) {
        return ServiceResponse.from(service.update(id, data(request)));
    }

    @PatchMapping("/{id}/active")
    public ServiceResponse setActive(@PathVariable("id") Long id, @Valid @RequestBody ServiceActiveRequest request) {
        return ServiceResponse.from(service.setActive(id, request.active()));
    }

    private static ServiceData data(ServiceWriteRequest request) {
        return new ServiceData(request.name(), request.description(), request.referencePrice(), request.durationMinutes());
    }
}
