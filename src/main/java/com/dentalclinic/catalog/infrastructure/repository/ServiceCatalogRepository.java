package com.dentalclinic.catalog.infrastructure.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;

import com.dentalclinic.catalog.domain.ServiceCatalogItem;

public interface ServiceCatalogRepository extends JpaRepository<ServiceCatalogItem, Long> {
    List<ServiceCatalogItem> findAllByOrderByNameAscIdAsc();
    List<ServiceCatalogItem> findByActiveOrderByNameAscIdAsc(boolean active);
    Optional<ServiceCatalogItem> findByIdAndActiveTrue(Long id);
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select item from ServiceCatalogItem item where item.id = :id")
    Optional<ServiceCatalogItem> findForBooking(Long id);
}
