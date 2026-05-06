package com.example.demo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = false)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Manager payload built around a base PersonDto")
public class ManagerDto {

    @Schema(example = "MANAGER")
    private String role;

    private PersonDto person;

    @Schema(example = "Sales North-East")
    private String teamName;

    @Schema(description = "Direct reports")
    private List<String> directReports;

    @Schema(example = "150000.00")
    private Double budgetUsd;

    @Schema(example = "Hello manager. Here is your team overview.")
    private String message;
}
