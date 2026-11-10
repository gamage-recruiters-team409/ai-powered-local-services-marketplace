package com.gamagerecruiters.marketplace.modules.customer.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import com.gamagerecruiters.marketplace.modules.customer.domain.CustomerAddress;

@Repository
public interface CustomerAddressRepository extends MongoRepository<CustomerAddress, String> {

    List<CustomerAddress> findByCustomerIdOrderByCreatedAtAsc(String customerId);

    Optional<CustomerAddress> findByIdAndCustomerId(String id, String customerId);

    long countByCustomerId(String customerId);

    Optional<CustomerAddress> findFirstByCustomerIdAndIsDefaultTrue(String customerId);

    Optional<CustomerAddress> findFirstByCustomerIdOrderByCreatedAtAsc(String customerId);
}
