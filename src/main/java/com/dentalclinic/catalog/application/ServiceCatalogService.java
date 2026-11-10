package com.dentalclinic.catalog.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.catalog.domain.ServiceCatalogItem;
import com.dentalclinic.catalog.domain.ServiceUnavailableException;
import com.dentalclinic.catalog.infrastructure.repository.ServiceCatalogRepository;
import com.dentalclinic.identity.domain.exception.NotFoundException;

/** API de aplicación del catálogo. F7.3 podrá resolver un servicio activo aquí, sin acceder al repositorio. */
@Service
@Transactional(readOnly = true)
public class ServiceCatalogService {
    private final ServiceCatalogRepository repository;
    private final Clock clock;

    public ServiceCatalogService(ServiceCatalogRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public List<ServiceCatalogItem> list(Boolean active) {
        return active == null ? repository.findAllByOrderByNameAscIdAsc()
                : repository.findByActiveOrderByNameAscIdAsc(active);
    }

    public ServiceCatalogItem get(Long id) {
        return repository.findById(id).orElseThrow(NotFoundException::new);
    }

    public ServiceCatalogItem getActive(Long id) {
        return repository.findByIdAndActiveTrue(id).orElseThrow(NotFoundException::new);
    }

    /** Lectura descriptiva para disponibilidad; la creación vuelve a validar bajo lock. */
    public BookingService resolveActive(Long id) {
        return bookingData(repository.findById(id)
                .orElseThrow(() -> new NotFoundException("El servicio seleccionado no existe.")));
    }

    /** El lock compartido se mantiene hasta el commit de creación; edición/desactivación esperan. */
    @Transactional
    public BookingService resolveForBooking(Long id) {
        return bookingData(repository.findForBooking(id)
                .orElseThrow(() -> new NotFoundException("El servicio seleccionado no existe.")));
    }

    private BookingService bookingData(ServiceCatalogItem item) {
        if (!item.isActive()) throw new ServiceUnavailableException();
        return new BookingService(item.getId(), item.getName(), item.getDescription(),
                item.getReferencePrice(), item.getDurationMinutes());
    }

    public record BookingService(Long id, String name, String description, BigDecimal referencePrice, int durationMinutes) { }

    @Transactional
    public ServiceCatalogItem create(ServiceData data) {
        return repository.save(ServiceCatalogItem.create(data.name(), data.description(),
                data.referencePrice(), data.durationMinutes(), clock.instant()));
    }

    @Transactional
    public ServiceCatalogItem update(Long id, ServiceData data) {
        ServiceCatalogItem item = get(id);
        item.update(data.name(), data.description(), data.referencePrice(), data.durationMinutes(), clock.instant());
        return item;
    }

    @Transactional
    public ServiceCatalogItem setActive(Long id, boolean active) {
        ServiceCatalogItem item = get(id);
        item.setActive(active, clock.instant());
        return item;
    }

    public record ServiceData(String name, String description, BigDecimal referencePrice, int durationMinutes) { }
}
