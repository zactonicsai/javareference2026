package com.example.demo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Product DTO used for CRUD operations")
public class ProductDto {

    @Schema(example = "1", description = "Server-generated id; ignored on POST")
    private Long id;

    @NotBlank
    @Size(min = 1, max = 100)
    @Schema(example = "Wireless Mouse")
    private String name;

    @Size(max = 500)
    @Schema(example = "Ergonomic 2.4GHz wireless mouse")
    private String description;

    @NotNull
    @DecimalMin(value = "0.0", inclusive = false)
    @Schema(example = "29.99")
    private BigDecimal price;

    @NotNull
    @Schema(example = "100")
    private Integer stock;
}
