package com.dentalclinic.catalog.api;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dentalclinic.catalog.api.dto.PublicServiceResponse;
import com.dentalclinic.catalog.application.ServiceCatalogService;

@RestController
@RequestMapping("/api/public/services")
public class PublicServiceCatalogController {
    private final ServiceCatalogService service;

    public PublicServiceCatalogController(ServiceCatalogService service) { this.service = service; }

    @GetMapping
    public List<PublicServiceResponse> list() {
        return service.list(true).stream().map(PublicServiceResponse::from).toList();
    }

    @GetMapping("/{id}")
    public PublicServiceResponse get(@PathVariable("id") Long id) {
        return PublicServiceResponse.from(service.getActive(id));
    }
}
