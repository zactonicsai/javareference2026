package com.example.demo.repository.pg;

import com.example.demo.entity.pg.ProductPg;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProductPgRepository extends JpaRepository<ProductPg, Long> {
    boolean existsByNameIgnoreCase(String name);
}
