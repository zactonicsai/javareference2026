package com.example.demo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Base person object")
public class PersonDto {
    @Schema(example = "John Doe")
    private String fullName;

    @Schema(example = "john.doe@example.com")
    private String email;

    @Schema(example = "+1-555-0100")
    private String phone;

    @Schema(example = "Operations")
    private String department;
}
